package com.roadmind.server.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class InfrastructureSmokeTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(
            DockerImageName.parse("mysql:8.4.11"))
            .withDatabaseName("roadmind")
            .withUsername("roadmind")
            .withPassword("test-only-password");

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:8.10.0"))
            .withExposedPorts(6379);

    @Test
    void migratesEmptyMysqlAndPingsRedis() {
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load();

        assertThat(flyway.migrate().migrationsExecuted)
                .as("the phase 1 through phase 10 schema must migrate from an empty database")
                .isEqualTo(10);
        assertThat(flyway.migrate().migrationsExecuted).isZero();

        RedisClient client = RedisClient.create(
                "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        try (StatefulRedisConnection<String, String> connection = client.connect()) {
            assertThat(connection.sync().ping()).isEqualTo("PONG");
        } finally {
            client.shutdown();
        }
    }
}
