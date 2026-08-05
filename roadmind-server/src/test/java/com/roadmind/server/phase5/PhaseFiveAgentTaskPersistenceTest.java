package com.roadmind.server.phase5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.roadmind.server.agent.AgentTaskPersistence;
import com.roadmind.server.agent.AgentTaskReplay;
import com.roadmind.server.agent.AgentTaskSnapshot;
import com.roadmind.server.agent.ConversationSnapshot;
import com.roadmind.server.conversation.ConversationPersistence;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
class PhaseFiveAgentTaskPersistenceTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(
            DockerImageName.parse("mysql:8.4.11"))
            .withDatabaseName("roadmind")
            .withUsername("roadmind")
            .withPassword("test-only-password");

    private static JdbcTemplate jdbcTemplate;
    private static ConversationPersistence conversationPersistence;
    private static AgentTaskPersistence taskPersistence;

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
        conversationPersistence = new ConversationPersistence(provider);
        taskPersistence = new AgentTaskPersistence(provider, objectMapper);
    }

    @BeforeEach
    void cleanRows() {
        jdbcTemplate.update("DELETE FROM agent_task");
        jdbcTemplate.update("DELETE FROM message");
        jdbcTemplate.update("DELETE FROM conversation");
    }

    @Test
    void taskSnapshotAndIdempotencyReplaySurviveRepositoryReload() {
        Instant now = Instant.now();
        String conversationId = "199000000000000011";
        String taskId = "199000000000000111";
        conversationPersistence.create(
                new ConversationSnapshot(
                        conversationId,
                        "任务恢复",
                        "ACTIVE",
                        "Asia/Shanghai",
                        now),
                "roadmind-demo");

        AgentTaskSnapshot created = new AgentTaskSnapshot(
                taskId,
                conversationId,
                "重启后查询车辆",
                "ACCEPTED",
                null,
                null,
                true,
                false,
                null,
                List.of(),
                0,
                0,
                now,
                now);
        taskPersistence.create("roadmind-demo", conversationId, "task-replay-key", "hash-a", created);

        Optional<AgentTaskSnapshot> loaded = taskPersistence.findById(taskId);
        assertThat(loaded).isPresent();
        assertThat(loaded.orElseThrow().goal()).isEqualTo("重启后查询车辆");
        assertThat(loaded.orElseThrow().status()).isEqualTo("ACCEPTED");

        AgentTaskSnapshot completed = new AgentTaskSnapshot(
                taskId,
                conversationId,
                created.goal(),
                "SUCCEEDED",
                "RULE_STUB",
                "rule-stub",
                true,
                false,
                "已完成",
                List.of(),
                0,
                0,
                now,
                now.plusSeconds(1));
        taskPersistence.save(completed);

        AgentTaskReplay replay = taskPersistence.findByIdempotencyKey(
                        "roadmind-demo", conversationId, "task-replay-key")
                .orElseThrow();
        assertThat(replay.requestHash()).isEqualTo("hash-a");
        assertThat(replay.snapshot().status()).isEqualTo("SUCCEEDED");
        assertThat(replay.snapshot().response()).isEqualTo("已完成");
    }
}
