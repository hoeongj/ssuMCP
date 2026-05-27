package com.ssuai.domain.chat.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.chat.config.ChatMemoryProperties;
import com.ssuai.domain.chat.memory.ChatConversationStore.Turn;

class ChatConversationStoreTests {

    @Test
    void appendsAndReturnsHistoryInOrder() {
        ChatConversationStore store = new ChatConversationStore(properties(12, Duration.ofMinutes(30), 1000));

        store.appendUser("c1", "오늘 학식 뭐야?");
        store.appendAssistant("c1", "학생식당은 후라이드치킨이 나와요.");
        store.appendUser("c1", "도담은?");

        List<Turn> history = store.history("c1");
        assertThat(history).hasSize(3);
        assertThat(history.get(0)).isEqualTo(new Turn(ChatConversationStore.ROLE_USER, "오늘 학식 뭐야?"));
        assertThat(history.get(1)).isEqualTo(
                new Turn(ChatConversationStore.ROLE_ASSISTANT, "학생식당은 후라이드치킨이 나와요."));
        assertThat(history.get(2)).isEqualTo(new Turn(ChatConversationStore.ROLE_USER, "도담은?"));
    }

    @Test
    void capsHistoryToMaxMessagesByDroppingOldest() {
        ChatConversationStore store = new ChatConversationStore(properties(4, Duration.ofMinutes(30), 1000));

        store.appendUser("c1", "u1");
        store.appendAssistant("c1", "a1");
        store.appendUser("c1", "u2");
        store.appendAssistant("c1", "a2");
        store.appendUser("c1", "u3");
        store.appendAssistant("c1", "a3");

        List<Turn> history = store.history("c1");
        assertThat(history).hasSize(4);
        assertThat(history.get(0).content()).isEqualTo("u2");
        assertThat(history.get(3).content()).isEqualTo("a3");
    }

    @Test
    void historyIsScopedPerConversationId() {
        ChatConversationStore store = new ChatConversationStore(properties(12, Duration.ofMinutes(30), 1000));

        store.appendUser("c1", "c1 message");
        store.appendUser("c2", "c2 message");

        assertThat(store.history("c1")).extracting(Turn::content).containsExactly("c1 message");
        assertThat(store.history("c2")).extracting(Turn::content).containsExactly("c2 message");
    }

    @Test
    void expiredHistoryIsTreatedAsEmpty() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-05-15T00:00:00Z"));
        Clock clock = Clock.fixed(now.get(), ZoneOffset.UTC);
        ChatConversationStore store = new ChatConversationStore(
                properties(12, Duration.ofMinutes(30), 1000),
                new AdjustableClock(now));

        store.appendUser("c1", "hello");
        now.set(now.get().plus(Duration.ofMinutes(31)));

        assertThat(store.history("c1")).isEmpty();
        clock.toString(); // keep reference to silence unused warning
    }

    @Test
    void capsTotalConversationsByEvictingLeastRecentlyUsed() {
        ChatConversationStore store = new ChatConversationStore(properties(12, Duration.ofMinutes(30), 2));

        store.appendUser("c1", "first");
        store.appendUser("c2", "second");
        store.appendUser("c3", "third");

        assertThat(store.history("c1")).isEmpty();
        assertThat(store.history("c2")).extracting(Turn::content).containsExactly("second");
        assertThat(store.history("c3")).extracting(Turn::content).containsExactly("third");
    }

    @Test
    void blankConversationIdIsIgnored() {
        ChatConversationStore store = new ChatConversationStore(properties(12, Duration.ofMinutes(30), 1000));

        store.appendUser("", "hello");
        store.appendUser(null, "world");

        assertThat(store.history("")).isEmpty();
        assertThat(store.history(null)).isEmpty();
        assertThat(store.size()).isZero();
    }

    @Test
    void blankContentIsIgnored() {
        ChatConversationStore store = new ChatConversationStore(properties(12, Duration.ofMinutes(30), 1000));

        store.appendUser("c1", "");
        store.appendUser("c1", null);
        store.appendUser("c1", "real");

        assertThat(store.history("c1")).hasSize(1).extracting(Turn::content).containsExactly("real");
    }

    @Test
    void clearRemovesConversation() {
        ChatConversationStore store = new ChatConversationStore(properties(12, Duration.ofMinutes(30), 1000));

        store.appendUser("c1", "u1");
        store.appendAssistant("c1", "a1");
        store.clear("c1");

        assertThat(store.history("c1")).isEmpty();
    }

    private static ChatMemoryProperties properties(int maxMessages, Duration ttl, int maxConversations) {
        ChatMemoryProperties properties = new ChatMemoryProperties();
        properties.setMaxMessages(maxMessages);
        properties.setTtl(ttl);
        properties.setMaxConversations(maxConversations);
        return properties;
    }

    private static final class AdjustableClock extends Clock {

        private final AtomicReference<Instant> instant;

        private AdjustableClock(AtomicReference<Instant> instant) {
            this.instant = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
