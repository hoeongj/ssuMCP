package com.ssuai.domain.library.connector;

import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@ConditionalOnProperty(name = "ssuai.connector.library-seat", havingValue = "real")
class LibrarySeatConnectorConfig {

    @Bean("librarySeatRestClient")
    RestClient librarySeatRestClient(LibrarySeatProperties properties, RestClient.Builder builder) {
        return builder
                .baseUrl(properties.getBaseUrl())
                .requestFactory(timeoutFactory(properties.getTimeout()))
                .build();
    }

    private static SimpleClientHttpRequestFactory timeoutFactory(Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int millis = (int) Math.min(Integer.MAX_VALUE, timeout.toMillis());
        factory.setConnectTimeout(millis);
        factory.setReadTimeout(millis);
        return factory;
    }
}
