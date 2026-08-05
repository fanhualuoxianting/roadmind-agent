package com.roadmind.server.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.roadmind.server.audit.AuditService;
import com.roadmind.server.preference.PreferenceRepository;
import com.roadmind.server.preference.PreferenceService;
import com.roadmind.server.preference.PreferenceSnapshot;
import com.roadmind.server.scheduling.ScheduledTaskIdempotencyConflictException;
import com.roadmind.server.scheduling.ScheduledTaskRepository;
import com.roadmind.server.scheduling.ScheduledTaskService;
import com.roadmind.server.scheduling.ScheduledTaskSnapshot;
import com.roadmind.server.workflow.CoreWorkflowModels.DeferredActionAuthorization;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class PersistenceIntegrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(
            DockerImageName.parse("mysql:8.4.11"))
            .withDatabaseName("roadmind")
            .withUsername("roadmind")
            .withPassword("test-only-password");

    private static JdbcTemplate jdbcTemplate;
    private static PreferenceService preferenceService;
    private static ScheduledTaskRepository scheduledTaskRepository;
    private static ScheduledTaskService scheduledTaskService;
    private static AuditService auditService;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);
        ObjectMapper objectMapper = new ObjectMapper();

        ObjectProvider<JdbcTemplate> jdbcProvider = mock(ObjectProvider.class);
        when(jdbcProvider.getIfAvailable()).thenReturn(jdbcTemplate);

        PreferenceRepository preferenceRepository = new PreferenceRepository(
                jdbcProvider, objectMapper);
        ObjectProvider<PreferenceRepository> preferenceProvider = mock(ObjectProvider.class);
        when(preferenceProvider.getIfAvailable()).thenReturn(preferenceRepository);
        preferenceService = new PreferenceService(preferenceProvider);

        scheduledTaskRepository = new ScheduledTaskRepository(jdbcProvider, objectMapper);
        ObjectProvider<ScheduledTaskRepository> taskProvider = mock(ObjectProvider.class);
        when(taskProvider.getIfAvailable()).thenReturn(scheduledTaskRepository);
        scheduledTaskService = new ScheduledTaskService(taskProvider, objectMapper);
        auditService = new AuditService(jdbcProvider, objectMapper);
    }

    @BeforeEach
    void cleanRows() {
        jdbcTemplate.update("DELETE FROM scheduled_task");
        jdbcTemplate.update("DELETE FROM user_preference");
        jdbcTemplate.update("DELETE FROM audit_event");
    }

    @Test
    void preferenceExpiryAliasUpdateAndDeleteAreBackedByMysql() {
        ObjectNode place = new ObjectMapper().createObjectNode().put("name", "无锡学院");
        PreferenceSnapshot saved = preferenceService.put(
                "roadmind-demo",
                "LOCATION",
                "学校",
                place,
                "SENSITIVE",
                Instant.now().plus(10, ChronoUnit.MINUTES),
                "preference-key-1");

        assertThat(saved.value().path("name").asText()).isEqualTo("无锡学院");
        assertThat(preferenceService.expandLocationAliases("roadmind-demo", "从南京到学校"))
                .isEqualTo("从南京到无锡学院");

        jdbcTemplate.update(
                "UPDATE user_preference SET expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)),
                saved.id());
        assertThat(preferenceService.list("roadmind-demo", "LOCATION", 30)).isEmpty();

        PreferenceSnapshot updated = preferenceService.put(
                "roadmind-demo",
                "LOCATION",
                "学校",
                new ObjectMapper().createObjectNode().put("name", "南京南站"),
                "SENSITIVE",
                Instant.now().plus(10, ChronoUnit.MINUTES),
                "preference-key-2");
        assertThat(updated.value().path("name").asText()).isEqualTo("南京南站");
        assertThat(preferenceService.delete(
                "roadmind-demo", "LOCATION", "学校", "preference-delete-1")).isTrue();
        assertThat(preferenceService.list("roadmind-demo", "LOCATION", 30)).isEmpty();
    }

    @Test
    void sameScheduledTaskKeyIsIdempotentAndDifferentPayloadConflicts() {
        Instant executeAt = Instant.now().plus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);
        ScheduledTaskSnapshot first = scheduledTaskService.createReminder(
                "roadmind-demo", "出发提醒", executeAt, "Asia/Shanghai", "schedule-key-1");
        ScheduledTaskSnapshot replay = scheduledTaskService.createReminder(
                "roadmind-demo", "出发提醒", executeAt, "Asia/Shanghai", "schedule-key-1");

        assertThat(replay.id()).isEqualTo(first.id());
        assertThatThrownBy(() -> scheduledTaskService.createReminder(
                "roadmind-demo", "另一条提醒", executeAt, "Asia/Shanghai", "schedule-key-1"))
                .isInstanceOf(ScheduledTaskIdempotencyConflictException.class);
    }

    @Test
    void twoWorkersOnlyClaimOneTaskAndExpiredLeaseCanBeRecovered() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        ScheduledTaskSnapshot created = scheduledTaskService.createReminder(
                "roadmind-demo",
                "到点执行",
                now.plus(30, ChronoUnit.MINUTES),
                "Asia/Shanghai",
                "schedule-key-2");
        jdbcTemplate.update(
                "UPDATE scheduled_task SET execute_at = ? WHERE id = ?",
                Timestamp.from(now.minusSeconds(1)),
                created.id());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<List<ScheduledTaskSnapshot>> workerA = executor.submit(() -> {
            start.await();
            return scheduledTaskRepository.claimDueTasks(
                    "worker-a", now, now.plusSeconds(30), 1);
        });
        Future<List<ScheduledTaskSnapshot>> workerB = executor.submit(() -> {
            start.await();
            return scheduledTaskRepository.claimDueTasks(
                    "worker-b", now, now.plusSeconds(30), 1);
        });
        start.countDown();
        List<ScheduledTaskSnapshot> claimsA = workerA.get();
        List<ScheduledTaskSnapshot> claimsB = workerB.get();
        executor.shutdownNow();

        assertThat(claimsA.size() + claimsB.size()).isEqualTo(1);
        jdbcTemplate.update(
                "UPDATE scheduled_task SET locked_until = ? WHERE id = ?",
                Timestamp.from(now.minusSeconds(1)),
                created.id());

        List<ScheduledTaskSnapshot> recovered = scheduledTaskRepository.claimDueTasks(
                "recovery-worker", now, now.plusSeconds(30), 1);
        assertThat(recovered).extracting(ScheduledTaskSnapshot::id).containsExactly(created.id());
        assertThat(scheduledTaskRepository.markRunning(created.id(), "recovery-worker", now)).isTrue();
        assertThat(scheduledTaskRepository.markSucceeded(
                created.id(), "recovery-worker", now.plusSeconds(1))).isTrue();
        assertThat(scheduledTaskRepository.findById(created.id()).orElseThrow().status())
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void deferredHomeTaskStoresOriginalWorkflowAuthorizationReference() {
        DeferredActionAuthorization authorization = new DeferredActionAuthorization(
                "workflow-001",
                "conversation-001",
                "s5",
                "home.set_light",
                2,
                "confirmation-001",
                "a".repeat(64),
                java.util.Map.of("deviceId", "demo-home-light-01", "on", false));
        ScheduledTaskSnapshot created = scheduledTaskService.createAuthorizedHomeControl(
                "roadmind-demo",
                authorization,
                Instant.now().plus(30, ChronoUnit.MINUTES),
                "Asia/Shanghai",
                "deferred-key-1");

        ScheduledTaskSnapshot loaded = scheduledTaskRepository.findById(created.id()).orElseThrow();
        assertThat(loaded.taskType()).isEqualTo("HOME_CONTROL");
        assertThat(loaded.authorizationWorkflowId()).isEqualTo("workflow-001");
        assertThat(loaded.authorizationStepId()).isEqualTo("s5");
        assertThat(loaded.authorizationConfirmationId()).isEqualTo("confirmation-001");
        assertThat(loaded.authorizationPlanVersion()).isEqualTo(2);
        assertThat(loaded.authorizationPayloadHash()).isEqualTo("a".repeat(64));
    }

    @Test
    void auditStoresTraceableFieldsWithSecretRedaction() {
        auditService.record("test.audit", "TEST", "trace-audit-001", "1", Map.of(
                "token", "do-not-store",
                "nested", Map.of("password", "also-do-not-store", "status", "ok")));

        String detail = jdbcTemplate.queryForObject(
                "SELECT detail_json FROM audit_event WHERE event_type = 'test.audit'",
                String.class);
        assertThat(detail).contains("[REDACTED]").doesNotContain("do-not-store", "also-do-not-store");
    }
}
