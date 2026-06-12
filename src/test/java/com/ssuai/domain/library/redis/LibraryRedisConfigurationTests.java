package com.ssuai.domain.library.redis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.redisson.config.Config;

class LibraryRedisConfigurationTests {

    @Test
    void redissonCustomizerEnablesLazyInitialization() {
        Config config = new Config();

        new LibraryRedisConfiguration()
                .redissonLazyInitializationCustomizer()
                .customize(config);

        assertThat(config.isLazyInitialization()).isTrue();
    }
}
