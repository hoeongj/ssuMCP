package com.ssuai.domain.lms.connector;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.OptionalLong;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ssuai.domain.auth.lms.LmsCookies;

@Component
@ConditionalOnProperty(name = "ssuai.connector.lms-materials", havingValue = "real")
public class HeadLmsMaterialSizeResolver implements LmsMaterialSizeResolver {

    @Override
    public OptionalLong resolve(
            HttpClient authenticatedClient,
            LmsCookies cookies,
            String absoluteDownloadUrl,
            Duration timeout) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(absoluteDownloadUrl))
                    .header("Cookie", cookies.rawCookieHeader())
                    .timeout(timeout)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> response = authenticatedClient.send(
                    request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return OptionalLong.empty();
            }
            return response.headers().firstValueAsLong("Content-Length");
        } catch (IOException | IllegalArgumentException exception) {
            return OptionalLong.empty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return OptionalLong.empty();
        }
    }
}
