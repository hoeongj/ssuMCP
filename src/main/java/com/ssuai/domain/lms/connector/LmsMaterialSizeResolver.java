package com.ssuai.domain.lms.connector;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.OptionalLong;

import com.ssuai.domain.auth.lms.LmsCookies;

public interface LmsMaterialSizeResolver {

    OptionalLong resolve(
            HttpClient authenticatedClient,
            LmsCookies cookies,
            String absoluteDownloadUrl,
            Duration timeout);
}
