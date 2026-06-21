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

    private static final String OWNER = "owner-a";

    @Test
    void appendsAndReturnsHistoryInOrder() {
        ChatConversationStore store = new ChatConversationStore(properties(12, Duration.ofMinutes(30), 1000));

        store.appendUser(OWNER, "c1", "오늘 학식 뭐야?");
        store.appendAssistant(OWNER, "c1", "학생식당은 후라이드치킨이 나와요.");
        store.appendUser(OWNER, "c1", "도담은?");

        List<Turn> history = store.history(OWNER, "c1");
        assertThat(history).hasSize(3);
        assertThat(history.get(0)).isEqualTo(new Turn(ChatConversationStore.ROLE_USER, "오늘 학식 뭐야?"));
        assertThat(history.get(1)).isEqualTo(
                new Turn(ChatConversationStore.ROLE_ASSISTANT, "학생식당은 후라이드치킨이 나와요."));
        assertThat(history.get(2)).isEqualTo(new Turn(ChatConversationStore.ROLE_USER, "도담은?"));
    }

    @Test
    void capsHistoryToMaxMessagesByDroppingOldest() {
        ChatConversationStore store = new ChatConversationStore(properties(4, Duration.ofMinutes(30), 1000));

        store.appendUser(OWNER, "c1", "u1");
        store.appendAssistant(OWNER, "c1", "a1");
        store.appendUser(OWNER, "c1", "u2");
        store.appendAssistant(OWNER, "c1", "a2");
        store.appendUser(OWNER, "c1", "u3");
        store.appendAssistant(OWNER, "c1", "a3");

        List<Turn> history = store.history(OWNER, "c1");
        assertThat(history).hasSize(4);
        assertThat(history.get(0).content()).isEqualTo("u2");
        assertThat(history.get(3).content()).isEqualTo("a3");
    }

    @Test
    void historyIsScopedPerConversationId() {
        ChatConversationStore store = new ChatConversationStore(properties(12, Duration.ofMinutes(30), 1000));

        store.appendUser(OWNER, "c1", "c1 message");
        store.appendUser(OWNER, "c2", "c2 message");

        assertThat(store.history(OWNER, "c1")).extracting(Turn::content).containsExactly("c1 message");
        assertThat(store.history(OWNER, "c2")).extracting(Turn::content).containsExactly("c2 message");
    }

    @Test
    void historyIsIsolatedPerOwnerForSharedConversationId() {
        ChatConversationStore store = new ChatConversationStore(properties(12, Duration.ofMinutes(30), 1000));

        // Owner A builds up history under the conversationId "shared".
        store.appendUser("owner-a", "shared", "A의 첫 메시지");
        store.appendAssistant("owner-a", "shared", "A에게 보낸 답변");

        // Owner B reuses (or guesses) the same conversationId "shared".
        // B MUST NOT see A's turns — cross-user history leak guard.
        assertThat(store.history("owner-b", "shared")).isEmpty();
        store.appendUser("owner-b", "shared", "B의 메시지");
        assertThat(store.history("owner-b", "shared"))
                .extracting(Turn::content)
                .containsExactly("B의 메시지");

        // A still sees only A's own turns under the same conversationId.
        assertThat(store.history("owner-a", "shared"))
                .extracting(Turn::content)
                .containsExactly("A의 첫 메시지", "A에게 보낸 답변");

        // Private marking is also per (owner, conversationId).
        store.markPrivate("owner-a", "shared");
        assertThat(store.isPrivate("owner-a", "shared")).isTrue();
        assertThat(store.isPrivate("owner-b", "shared")).isFalse();
    }

    @Test
    void expiredHistoryIsTreatedAsEmpty() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-05-15T00:00:00Z"));
        Clock clock = Clock.fixed(now.get(), ZoneOffset.UTC);
        ChatConversationStore store = new ChatConversationStore(
                properties(12, Duration.ofMinutes(30), 1000),
                new AdjustableClock(now));

        store.appendUser(OWNER, "c1", "hello");
        now.set(now.get().plus(Duration.ofMinutes(31)));

        assertThat(store.history(OWNER, "c1")).isEmpty();
        clock.toString(); // keep reference to silence unused warning
    }

    @Test
    void capsTotalConversationsByEvictingLeastRecentlyUsed() {
        ChatConversationStore store = new ChatConversationStore(properties(12, Duration.ofMinutes(30), 2));

        store.appendUser(OWNER, "c1", "first");
        store.appendUser(OWNER, "c2", "second");
        store.appendUser(OWNER, "c3", "third");

        assertThat(store.history(OWNER, "c1")).isEmpty();
        assertThat(store.history(OWNER, "c2")).extracting(Turn::content).containsExactly("second");
        assertThat(store.history(OWNER, "c3")).extracting(Turn::content).containsExactly("third");
    }

    @Test
    void blankOwnerOrConversationIdIsIgnored() {
        ChatConversationStore store = new ChatConversationStore(properties(12, Duration.ofMinutes(30), 1000));

        store.appendUser(OWNER, "", "hello");
        store.appendUser(OWNER, null, "world");
        store.appendUser("", "c1", "blank owner");
        store.appendUser(null, "c1", "null owner");

        assertThat(store.history(OWNER, "")).isEmpty();
        assertThat(store.history(OWNER, null)).isEmpty();
        assertThat(store.history("", "c1")).isEmpty();
        assertThat(store.history(null, "c1")).isEmpty();
        assertThat(store.size()).isZero();
    }

    @Test
    void blankContentIsIgnored() {
        ChatConversationStore store = new ChatConversationStore(properties(12, Duration.ofMinutes(30), 1000));

        store.appendUser(OWNER, "c1", "");
        store.appendUser(OWNER, "c1", null);
        store.appendUser(OWNER, "c1", "real");

        assertThat(store.history(OWNER, "c1")).hasSize(1).extracting(Turn::content).containsExactly("real");
    }

    @Test
    void clearRemovesConversation() {
        ChatConversationStore store = new ChatConversationStore(properties(12, Duration.ofMinutes(30), 1000));

        store.appendUser(OWNER, "c1", "u1");
        store.appendAssistant(OWNER, "c1", "a1");
        store.clear(OWNER, "c1");

        assertThat(store.history(OWNER, "c1")).isEmpty();
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
