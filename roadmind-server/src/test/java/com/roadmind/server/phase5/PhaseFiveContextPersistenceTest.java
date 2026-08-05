package com.roadmind.server.phase5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.roadmind.server.agent.ConversationSnapshot;
import com.roadmind.server.conversation.ConversationContextCache;
import com.roadmind.server.conversation.ConversationContextService;
import com.roadmind.server.conversation.ConversationContextSnapshot;
import com.roadmind.server.conversation.ConversationPersistence;
import java.time.Instant;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class PhaseFiveContextPersistenceTest {

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

    private static JdbcTemplate jdbcTemplate;
    private static StringRedisTemplate redisTemplate;
    private static ConversationContextService contextService;

    @BeforeAll
    static void startPersistence() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        ObjectProvider<JdbcTemplate> jdbcProvider = mock(ObjectProvider.class);
        when(jdbcProvider.getIfAvailable()).thenReturn(jdbcTemplate);

        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(redisTemplate);

        ConversationPersistence persistence = new ConversationPersistence(jdbcProvider);
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        objectMapper.findAndRegisterModules();
        ConversationContextCache cache = new ConversationContextCache(redisProvider, objectMapper);
        contextService = new ConversationContextService(persistence, cache);
    }

    @BeforeEach
    void cleanRowsAndCache() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        jdbcTemplate.update("DELETE FROM message");
        jdbcTemplate.update("DELETE FROM conversation");
    }

    @Test
    void redisClearRecoversConversationContextFromMysql() {
        String id = Long.toString(199000000000000001L);
        ConversationSnapshot conversation = new ConversationSnapshot(
                id,
                "上下文恢复",
                "ACTIVE",
                "Asia/Shanghai",
                Instant.now());

        contextService.created("roadmind-demo", conversation);
        Optional<ConversationContextSnapshot> appended = contextService.appendUserMessage(
                "roadmind-demo",
                id,
                "从南京到学校");

        assertThat(appended).isPresent();
        assertThat(appended.orElseThrow().contextVersion()).isEqualTo(1);
        assertThat(appended.orElseThrow().recentMessages()).containsExactly("从南京到学校");
        assertThat(redisTemplate.hasKey("roadmind:session:" + id + ":context")).isTrue();

        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();

        Optional<ConversationContextSnapshot> recovered = contextService.recover("roadmind-demo", id);
        assertThat(recovered).isPresent();
        assertThat(recovered.orElseThrow().contextVersion()).isEqualTo(1);
        assertThat(recovered.orElseThrow().recentMessages()).containsExactly("从南京到学校");
        assertThat(redisTemplate.hasKey("roadmind:session:" + id + ":context")).isTrue();
    }
}
