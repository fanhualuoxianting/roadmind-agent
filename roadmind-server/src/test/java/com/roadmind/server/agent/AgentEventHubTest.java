package com.roadmind.server.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentEventHubTest {

    private MutableClock clock;
    private AgentEventHub hub;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-08-05T00:00:00Z"));
        hub = new AgentEventHub(clock);
    }

    @Test
    void completedChannelIsRetainedForReplayWindowThenRemoved() {
        hub.create("task-1");
        hub.publish("task-1", "trace-1", "agent.task.updated", Map.of("status", "RUNNING"));
        hub.complete("task-1");

        hub.cleanupExpiredChannels();
        assertThat(hub.channelCount()).isEqualTo(1);

        clock.advance(Duration.ofMinutes(14).plusSeconds(59));
        hub.cleanupExpiredChannels();
        assertThat(hub.channelCount()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(1));
        hub.cleanupExpiredChannels();
        assertThat(hub.channelCount()).isZero();
        assertThatThrownBy(() -> hub.subscribe("task-1"))
                .isInstanceOf(AgentResourceNotFoundException.class);
    }

    @Test
    void activeChannelIsNotRemovedAndRepeatedCompleteDoesNotExtendRetention() {
        hub.create("active-task");
        hub.create("completed-task");
        hub.complete("completed-task");

        clock.advance(Duration.ofMinutes(10));
        hub.complete("completed-task");
        clock.advance(Duration.ofMinutes(5));
        hub.cleanupExpiredChannels();

        assertThat(hub.channelCount()).isEqualTo(1);
        hub.publish("active-task", "trace-active", "agent.task.updated", Map.of("status", "RUNNING"));
    }

    @Test
    void restoresExactlyOneCompletedReplayForTerminalSnapshot() {
        Instant now = clock.instant();
        AgentTaskSnapshot snapshot = new AgentTaskSnapshot(
                "recovered-task",
                "conversation-1",
                "恢复任务",
                "SUCCEEDED",
                "RULE_STUB",
                "roadmind-rule-fixture",
                true,
                false,
                "任务已完成",
                List.of(),
                0,
                0,
                now,
                now);

        hub.restoreCompleted(snapshot);
        hub.restoreCompleted(snapshot);

        assertThat(hub.hasChannel(snapshot.taskId())).isTrue();
        assertThat(hub.eventTypes(snapshot.taskId()))
                .containsExactly("agent.response.ready", "stream.complete");
        assertThat(hub.subscribe(snapshot.taskId())).isNotNull();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
