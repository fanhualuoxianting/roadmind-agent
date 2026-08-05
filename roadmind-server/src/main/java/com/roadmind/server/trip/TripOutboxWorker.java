package com.roadmind.server.trip;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "roadmind.trip.outbox",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
class TripOutboxWorker {

    private final TripOutboxRepository repository;
    private final TripEventHub events;
    private final Clock clock = Clock.systemUTC();
    private final String workerId = "trip-outbox-" + UUID.randomUUID();

    TripOutboxWorker(TripOutboxRepository repository, TripEventHub events) {
        this.repository = repository;
        this.events = events;
    }

    @Scheduled(fixedDelayString = "${roadmind.trip.outbox.poll-ms:500}")
    void poll() {
        if (!repository.isAvailable()) return;
        Instant now = clock.instant();
        for (TripOutboxEvent outbox : repository.claimDue(workerId, now, now.plusSeconds(30), 50)) {
            try {
                events.publishPrepared(new TripEventEnvelope(
                        1,
                        outbox.eventId(),
                        outbox.sequence(),
                        outbox.eventType(),
                        outbox.traceId(),
                        outbox.aggregateId(),
                        outbox.occurredAt(),
                        outbox.data()));
                repository.markDelivered(outbox.id(), workerId, clock.instant());
            } catch (RuntimeException exception) {
                repository.markFailed(outbox.id(), workerId, "OUTBOX_DELIVERY_FAILED", clock.instant());
            }
        }
    }
}
