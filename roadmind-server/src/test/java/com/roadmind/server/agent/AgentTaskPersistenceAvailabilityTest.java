package com.roadmind.server.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;

class AgentTaskPersistenceAvailabilityTest {

    @Test
    @SuppressWarnings("unchecked")
    void configuredButDisconnectedDatabaseDegradesToEmptyReadsAndNoOpWrites() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new FailingDataSource());
        ObjectProvider<JdbcTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(jdbcTemplate);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        AgentTaskPersistence persistence = new AgentTaskPersistence(provider, objectMapper);
        Instant now = Instant.parse("2026-08-05T00:00:00Z");
        AgentTaskSnapshot snapshot = new AgentTaskSnapshot(
                "199000000000000111",
                "199000000000000011",
                "数据库故障时继续缓存",
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

        assertThat(persistence.isAvailable()).isTrue();
        assertThatCode(() -> persistence.create(
                "roadmind-demo",
                snapshot.conversationId(),
                "outage-key",
                "request-hash",
                snapshot)).doesNotThrowAnyException();
        assertThatCode(() -> persistence.save(snapshot)).doesNotThrowAnyException();

        assertThat(persistence.findById(snapshot.taskId())).isEmpty();
        assertThat(persistence.findByIdForUser(snapshot.taskId(), "roadmind-demo")).isEmpty();
        assertThat(persistence.findByIdempotencyKey(
                "roadmind-demo",
                snapshot.conversationId(),
                "outage-key")).isEmpty();
    }

    private static final class FailingDataSource extends AbstractDataSource {
        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLTransientConnectionException("database unavailable");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            throw new SQLTransientConnectionException("database unavailable");
        }
    }
}
