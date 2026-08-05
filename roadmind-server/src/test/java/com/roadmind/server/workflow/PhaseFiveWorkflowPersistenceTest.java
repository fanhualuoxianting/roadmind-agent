package com.roadmind.server.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.roadmind.server.workflow.CoreWorkflowModels.Snapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
class PhaseFiveWorkflowPersistenceTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(
            DockerImageName.parse("mysql:8.4.11"))
            .withDatabaseName("roadmind")
            .withUsername("roadmind")
            .withPassword("test-only-password");

    private static JdbcTemplate jdbcTemplate;
    private static CoreWorkflowPersistence persistence;

    @BeforeAll
    static void startPersistence() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        ObjectProvider<JdbcTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(jdbcTemplate);
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        persistence = new CoreWorkflowPersistence(provider, objectMapper);
    }

    @BeforeEach
    void cleanRows() {
        jdbcTemplate.update("DELETE FROM workflow_state");
    }

    @Test
    void confirmationSnapshotAndDecisionSurviveServiceRestart() {
        Instant startedAt = Instant.parse("2026-08-04T00:00:00Z");
        Clock clock = Clock.fixed(startedAt, ZoneOffset.UTC);
        String message = "明天早上8点从南京软件谷出发去苏州";

        CoreWorkflowService first = new CoreWorkflowService(clock, persistence);
        Snapshot planned = first.message("workflow-recovery", message);

        assertThat(planned.status()).isEqualTo("WAITING_CONFIRMATION");
        assertThat(persistence.findById(planned.workflowId())).contains(planned);

        CoreWorkflowService restarted = new CoreWorkflowService(clock, persistence);
        Snapshot restored = restarted.get(planned.workflowId());
        assertThat(restored.status()).isEqualTo("WAITING_CONFIRMATION");
        assertThat(restored.planVersion()).isEqualTo(planned.planVersion());
        assertThat(restored.confirmation().payloadHash())
                .isEqualTo(planned.confirmation().payloadHash());

        Snapshot approved = restarted.decide(
                planned.workflowId(),
                "APPROVE",
                planned.planVersion(),
                planned.confirmation().payloadHash());
        assertThat(approved.status()).isEqualTo("SUCCEEDED");

        Snapshot replay = new CoreWorkflowService(clock, persistence).decide(
                planned.workflowId(),
                "APPROVE",
                planned.planVersion(),
                planned.confirmation().payloadHash());
        assertThat(replay.status()).isEqualTo("SUCCEEDED");
        assertThat(replay.timeline()).hasSameSizeAs(approved.timeline());
    }

    @Test
    void expiredConfirmationIsRestoredButCannotExecute() {
        Instant startedAt = Instant.parse("2026-08-04T01:00:00Z");
        Clock initialClock = Clock.fixed(startedAt, ZoneOffset.UTC);
        Snapshot planned = new CoreWorkflowService(initialClock, persistence)
                .message("workflow-expiry", "明天早上8点从南京软件谷出发去苏州");

        Clock afterExpiry = Clock.fixed(startedAt.plusSeconds(11 * 60L), ZoneOffset.UTC);
        Snapshot expired = new CoreWorkflowService(afterExpiry, persistence).decide(
                planned.workflowId(),
                "APPROVE",
                planned.planVersion(),
                planned.confirmation().payloadHash());

        assertThat(expired.status()).isEqualTo("CONFIRMATION_EXPIRED");
        assertThat(expired.confirmation().status()).isEqualTo("EXPIRED");
        assertThat(expired.steps()).allMatch(step -> step.status().equals("PENDING"));
        assertThat(persistence.findById(planned.workflowId())).contains(expired);
    }
}
