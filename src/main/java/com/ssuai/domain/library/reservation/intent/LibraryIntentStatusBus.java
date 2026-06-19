package com.ssuai.domain.library.reservation.intent;

import java.util.function.Consumer;

public interface LibraryIntentStatusBus {

    void publish(LibraryIntentStatusMessage message);

    Subscription subscribe(Consumer<LibraryIntentStatusMessage> listener);

    interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    static LibraryIntentStatusBus noop() {
        return new LibraryIntentStatusBus() {
            @Override
            public void publish(LibraryIntentStatusMessage message) {
            }

            @Override
            public Subscription subscribe(Consumer<LibraryIntentStatusMessage> listener) {
                return () -> {
                };
            }
        };
    }
}
