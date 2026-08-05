package com.roadmind.server.trip;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class TripEventHub {
    private static final long EMITTER_TIMEOUT_MS = 65_000L;
    private static final int MAX_REPLAY_EVENTS = 120;
    private static final int MAX_ACTIVE_EMITTERS = 32;
    private final Map<String, Channel> channels = new ConcurrentHashMap<>();
    private final Clock clock = Clock.systemUTC();

    public void create(String tripId) {
        create(tripId, 0);
    }

    public void create(String tripId, long initialSequence) {
        channels.putIfAbsent(tripId, new Channel(tripId, initialSequence));
    }

    public TripEventEnvelope publish(String tripId, String traceId, String type, Map<String, Object> data) {
        TripEventEnvelope event = prepare(tripId, traceId, type, data);
        publishPrepared(event);
        return event;
    }

    public TripEventEnvelope prepare(String tripId, String traceId, String type, Map<String, Object> data) {
        return requireChannel(tripId).prepare(traceId, type, data);
    }

    public void publishPrepared(TripEventEnvelope event) {
        requireChannel(event.tripId()).publishPrepared(event);
    }

    public SseEmitter subscribe(String tripId, String lastEventId, Map<String, Object> current) {
        return requireChannel(tripId).subscribe(lastEventId, current);
    }

    private Channel requireChannel(String tripId) {
        Channel channel = channels.get(tripId);
        if (channel == null) throw new TripNotFoundException(tripId);
        return channel;
    }

    private final class Channel {
        private final String tripId;
        private final List<TripEventEnvelope> history = new ArrayList<>();
        private final List<SseEmitter> emitters = new ArrayList<>();
        private long sequence;

        private Channel(String tripId, long initialSequence) {
            this.tripId = tripId;
            this.sequence = Math.max(0, initialSequence);
        }

        synchronized TripEventEnvelope prepare(String traceId, String type, Map<String, Object> data) {
            return envelope(++sequence, traceId, type, data);
        }

        synchronized void publishPrepared(TripEventEnvelope event) {
            if (history.stream().anyMatch(existing -> existing.eventId().equals(event.eventId()))) {
                return;
            }
            sequence = Math.max(sequence, event.sequence());
            history.add(event);
            if (history.size() > MAX_REPLAY_EVENTS) history.removeFirst();
            Iterator<SseEmitter> iterator = emitters.iterator();
            while (iterator.hasNext()) {
                SseEmitter emitter = iterator.next();
                if (!send(emitter, event)) iterator.remove();
            }
        }

        synchronized SseEmitter subscribe(String lastEventId, Map<String, Object> current) {
            SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
            long afterSequence = sequenceFrom(lastEventId);
            long oldest = history.isEmpty() ? sequence + 1 : history.getFirst().sequence();
            if (afterSequence > 0 && afterSequence < oldest - 1) {
                send(emitter, envelope(sequence, "replay-window", "trip.snapshot", current));
            } else {
                history.stream().filter(event -> event.sequence() > afterSequence).forEach(event -> send(emitter, event));
            }
            if (emitters.size() >= MAX_ACTIVE_EMITTERS) {
                emitter.completeWithError(new IllegalStateException("Trip SSE connection limit reached"));
            } else {
                emitters.add(emitter);
                emitter.onCompletion(() -> remove(emitter));
                emitter.onTimeout(() -> remove(emitter));
                emitter.onError(ignored -> remove(emitter));
            }
            return emitter;
        }

        private TripEventEnvelope envelope(long eventSequence, String traceId, String type, Map<String, Object> data) {
            return new TripEventEnvelope(1, tripId + ':' + eventSequence, eventSequence, type, traceId, tripId,
                    clock.instant(), Map.copyOf(new LinkedHashMap<>(data)));
        }

        private long sequenceFrom(String eventId) {
            if (eventId == null || eventId.isBlank()) return 0;
            int separator = eventId.lastIndexOf(':');
            try {
                return separator < 0 ? 0 : Long.parseLong(eventId.substring(separator + 1));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }

        private boolean send(SseEmitter emitter, TripEventEnvelope event) {
            try {
                emitter.send(SseEmitter.event().id(event.eventId()).name(event.type()).data(event));
                return true;
            } catch (IOException | IllegalStateException exception) {
                emitter.completeWithError(exception);
                return false;
            }
        }

        private synchronized void remove(SseEmitter emitter) {
            emitters.remove(emitter);
        }
    }
}
