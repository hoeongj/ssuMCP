package com.ssuai.domain.library.redis;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.ssuai.domain.library.dto.PyxisSeatInfo;

public interface LibraryRoomSeatL2Cache {

    Optional<List<PyxisSeatInfo>> get(int roomId, boolean authenticated);

    void put(int roomId, boolean authenticated, List<PyxisSeatInfo> seats, Duration ttl);

    static LibraryRoomSeatL2Cache noop() {
        return new LibraryRoomSeatL2Cache() {
            @Override
            public Optional<List<PyxisSeatInfo>> get(int roomId, boolean authenticated) {
                return Optional.empty();
            }

            @Override
            public void put(int roomId, boolean authenticated, List<PyxisSeatInfo> seats, Duration ttl) {
                // No-op fallback for tests and explicit Redis opt-out.
            }
        };
    }
}
