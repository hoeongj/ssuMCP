package com.ssuai.domain.auth.saint;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
class SaintSsoConfig {

    @Bean
    RestClient saintSsoRestClient(SaintSsoProperties properties) {
        return RestClient.builder()
                .requestFactory(timeoutFactory(properties.getTimeout()))
                .build();
    }

    private static SimpleClientHttpRequestFactory timeoutFactory(Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod)
                    throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        int millis = (int) Math.min(Integer.MAX_VALUE, timeout.toMillis());
        factory.setConnectTimeout(millis);
        factory.setReadTimeout(millis);
        return factory;
    }
}
