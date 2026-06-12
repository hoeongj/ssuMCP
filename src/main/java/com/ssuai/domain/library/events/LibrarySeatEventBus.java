package com.ssuai.domain.library.events;

import java.util.function.Consumer;

public interface LibrarySeatEventBus {

    void publish(LibrarySeatEvent event);

    Subscription subscribe(Consumer<LibrarySeatEvent> listener);

    interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    static LibrarySeatEventBus noop() {
        return new LibrarySeatEventBus() {
            @Override
            public void publish(LibrarySeatEvent event) {
                // No-op fallback for tests and explicit Redis opt-out.
            }

            @Override
            public Subscription subscribe(Consumer<LibrarySeatEvent> listener) {
                return () -> {
                };
            }
        };
    }
}
