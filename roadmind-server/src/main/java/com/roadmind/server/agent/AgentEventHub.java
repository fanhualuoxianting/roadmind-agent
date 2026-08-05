package com.roadmind.server.agent;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class AgentEventHub {

    private static final long EMITTER_TIMEOUT_MS = 65_000L;
    private static final int MAX_REPLAY_EVENTS = 100;
    private static final int MAX_ACTIVE_EMITTERS = 32;
    private static final Duration COMPLETED_CHANNEL_RETENTION = Duration.ofMinutes(15);

    private final Map<String, Channel> channels = new ConcurrentHashMap<>();
    private final Clock clock;

    public AgentEventHub() {
        this(Clock.systemUTC());
    }

    AgentEventHub(Clock clock) {
        this.clock = clock;
    }

    public void create(String taskId) {
        channels.putIfAbsent(taskId, new Channel(taskId));
    }

    public boolean hasChannel(String taskId) {
        return channels.containsKey(taskId);
    }

    public void restoreCompleted(AgentTaskSnapshot snapshot) {
        Channel channel = channels.computeIfAbsent(snapshot.taskId(), Channel::new);
        channel.restoreCompleted(snapshot);
    }

    public AgentEventEnvelope publish(String taskId, String traceId, String type, Map<String, Object> data) {
        Channel channel = channel(taskId);
        return channel.publish(traceId, type, data);
    }

    public void complete(String taskId) {
        channel(taskId).complete();
    }

    public SseEmitter subscribe(String taskId) {
        return channel(taskId).subscribe();
    }

    @Scheduled(fixedDelayString = "${roadmind.agent.events.cleanup-interval-ms:60000}")
    void cleanupExpiredChannels() {
        Instant cutoff = clock.instant().minus(COMPLETED_CHANNEL_RETENTION);
        channels.entrySet().removeIf(entry -> entry.getValue().completedAtOrBefore(cutoff));
    }

    int channelCount() {
        return channels.size();
    }

    List<String> eventTypes(String taskId) {
        return channel(taskId).eventTypes();
    }

    private Channel channel(String taskId) {
        Channel channel = channels.get(taskId);
        if (channel == null) {
            throw new AgentResourceNotFoundException("Agent 事件流", taskId);
        }
        return channel;
    }

    private final class Channel {
        private final String taskId;
        private final List<AgentEventEnvelope> history = new ArrayList<>();
        private final List<SseEmitter> emitters = new ArrayList<>();
        private long sequence;
        private boolean completed;
        private Instant completedAt;

        private Channel(String taskId) {
            this.taskId = taskId;
        }

        synchronized AgentEventEnvelope publish(String traceId, String type, Map<String, Object> data) {
            sequence++;
            AgentEventEnvelope event = new AgentEventEnvelope(
                    1,
                    taskId + ":" + sequence,
                    sequence,
                    type,
                    traceId,
                    taskId,
                    clock.instant(),
                    Map.copyOf(new LinkedHashMap<>(data)));
            history.add(event);
            if (history.size() > MAX_REPLAY_EVENTS) {
                history.remove(0);
            }
            Iterator<SseEmitter> iterator = emitters.iterator();
            while (iterator.hasNext()) {
                SseEmitter emitter = iterator.next();
                if (!send(emitter, event)) {
                    iterator.remove();
                }
            }
            return event;
        }

        synchronized void restoreCompleted(AgentTaskSnapshot snapshot) {
            if (completed || !history.isEmpty()) {
                return;
            }
            String traceId = "recovery-" + taskId;
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", snapshot.status());
            response.put("response", snapshot.response() == null ? "任务已结束" : snapshot.response());
            response.put("degraded", snapshot.degraded());
            response.put("recovered", true);
            if (snapshot.plannerMode() != null) {
                response.put("plannerMode", snapshot.plannerMode());
            }
            publish(traceId, "agent.response.ready", response);
            publish(traceId, "stream.complete", Map.of(
                    "finalStatus", snapshot.status(),
                    "recovered", true));
            complete();
        }

        synchronized SseEmitter subscribe() {
            SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
            for (AgentEventEnvelope event : history) {
                if (!send(emitter, event)) {
                    emitter.complete();
                    return emitter;
                }
            }
            if (completed) {
                emitter.complete();
            } else if (emitters.size() >= MAX_ACTIVE_EMITTERS) {
                emitter.completeWithError(new IllegalStateException("Agent SSE connection limit reached"));
            } else {
                emitters.add(emitter);
                emitter.onCompletion(() -> remove(emitter));
                emitter.onTimeout(() -> remove(emitter));
                emitter.onError(ignored -> remove(emitter));
            }
            return emitter;
        }

        synchronized void complete() {
            if (completed) {
                return;
            }
            completed = true;
            completedAt = clock.instant();
            emitters.forEach(SseEmitter::complete);
            emitters.clear();
        }

        synchronized boolean completedAtOrBefore(Instant cutoff) {
            return completedAt != null && !completedAt.isAfter(cutoff);
        }

        synchronized List<String> eventTypes() {
            return history.stream().map(AgentEventEnvelope::type).toList();
        }

        private boolean send(SseEmitter emitter, AgentEventEnvelope event) {
            try {
                emitter.send(SseEmitter.event()
                        .id(event.eventId())
                        .name(event.type())
                        .data(event));
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
