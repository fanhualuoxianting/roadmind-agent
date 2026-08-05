package com.roadmind.server.trip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.roadmind.server.adapter.simulator.SimulatorTripAdapter;
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
class TripPersistenceIntegrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(
            DockerImageName.parse("mysql:8.4.11"))
            .withDatabaseName("roadmind")
            .withUsername("roadmind")
            .withPassword("test-only-password");

    private static JdbcTemplate jdbcTemplate;
    private static TripPersistence persistence;
    private static TripOutboxRepository outbox;

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
        persistence = new TripPersistence(provider, objectMapper);
        outbox = new TripOutboxRepository(provider, objectMapper);
    }

    @BeforeEach
    void cleanRows() {
        jdbcTemplate.update("DELETE FROM domain_event_outbox");
        jdbcTemplate.update("DELETE FROM trip_event");
        jdbcTemplate.update("DELETE FROM trip");
        jdbcTemplate.update("DELETE FROM route_plan");
    }

    @Test
    void activeTripSnapshotAndRouteSurviveServiceRestart() {
        SimulatorTripAdapter simulator = mock(SimulatorTripAdapter.class);
        when(simulator.configure(anyString(), anyString(), any(), anyDouble(), anyString()))
                .thenAnswer(invocation -> telemetry(1, TripStatus.READY));
        when(simulator.command(anyString(), anyString(), any(), anyString()))
                .thenReturn(telemetry(2, TripStatus.DRIVING));

        TripService first = new TripService(new StubTripRoutePlanner(), simulator, new TripEventHub(), persistence);
        TripSnapshot created = first.create(
                new CreateTripRequest("南京软件谷", "无锡学院", true, 42), "trip-recovery-create");

        Optional<TripSnapshot> durable = persistence.findById(created.tripId());
        assertThat(durable).isPresent();
        assertThat(durable.orElseThrow().tripId()).isEqualTo(created.tripId());
        assertThat(durable.orElseThrow().status()).isEqualTo(created.status());
        assertThat(durable.orElseThrow().route().routeVersion())
                .isEqualTo(created.route().routeVersion());
        assertThat(durable.orElseThrow().route().routeHash())
                .isEqualTo(created.route().routeHash());
        assertThat(durable.orElseThrow().telemetry().sequence())
                .isEqualTo(created.telemetry().sequence());
        assertThat(durable.orElseThrow().lowBatteryReplanned())
                .isEqualTo(created.lowBatteryReplanned());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM domain_event_outbox WHERE aggregate_type = 'TRIP' AND aggregate_id = ?",
                Long.class,
                created.tripId())).isGreaterThanOrEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM domain_event_outbox WHERE aggregate_id = ? AND event_type = 'trip.snapshot'",
                Long.class,
                created.tripId())).isEqualTo(1);

        TripService restarted = new TripService(
                new StubTripRoutePlanner(), simulator, new TripEventHub(), persistence);
        TripSnapshot restored = restarted.get(created.tripId());
        assertThat(restored.status()).isEqualTo(TripStatus.READY);
        assertThat(restored.telemetry().sequence()).isEqualTo(created.telemetry().sequence());
        assertThat(restored.route().routeHash()).isEqualTo(created.route().routeHash());
        assertThat(restored.lowBatteryReplanned()).isTrue();

        TripSnapshot driving = restarted.command(
                created.tripId(), new TripCommandRequest("START", 5), "trip-recovery-start");
        assertThat(driving.status()).isEqualTo(TripStatus.DRIVING);
        assertThat(new TripService(
                new StubTripRoutePlanner(), simulator, new TripEventHub(), persistence)
                .get(created.tripId()).telemetry().sequence()).isEqualTo(2);
    }

    @Test
    void durableTripEventsAreClaimedByOnlyOneWorker() {
        SimulatorTripAdapter simulator = mock(SimulatorTripAdapter.class);
        when(simulator.configure(anyString(), anyString(), any(), anyDouble(), anyString()))
                .thenAnswer(invocation -> telemetry(1, TripStatus.READY));

        TripSnapshot created = new TripService(
                new StubTripRoutePlanner(), simulator, new TripEventHub(), persistence)
                .create(new CreateTripRequest("南京软件谷", "无锡学院", false, 80), "outbox-claim-create");

        Instant now = Instant.now().plusSeconds(2);
        List<TripOutboxEvent> claimed = outbox.claimDue(
                "worker-a", now, now.plusSeconds(30), 10);
        assertThat(claimed).isNotEmpty();
        assertThat(outbox.claimDue("worker-b", now, now.plusSeconds(30), 10)).isEmpty();

        TripOutboxEvent first = claimed.get(0);
        assertThat(outbox.markDelivered(first.id(), "worker-a", now)).isTrue();
        assertThat(outbox.markDelivered(first.id(), "worker-b", now)).isFalse();
    }

    private TripTelemetry telemetry(long sequence, TripStatus status) {
        return new TripTelemetry(
                sequence,
                new RouteCoordinate(118.7358, 31.9827, "GCJ-02"),
                status == TripStatus.DRIVING ? 90 : 0,
                90,
                42,
                254,
                0,
                169_000,
                Instant.parse("2026-08-05T02:18:00Z"),
                status,
                status == TripStatus.DRIVING ? 5 : 1,
                Instant.parse("2026-08-05T00:00:00Z"));
    }
}
