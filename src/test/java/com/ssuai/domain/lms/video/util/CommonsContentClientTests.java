package com.ssuai.domain.lms.video.util;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.configureFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.ssuai.domain.lms.video.dto.ContentInfo;
import com.ssuai.domain.lms.video.properties.LmsVideoProperties;

class CommonsContentClientTests {

    private static final String SAMPLE_XML = """
            <?xml version="1.0"?><content version="1.0"><content_playing_info version="1.0"><content_id>60b9b2d69431f</content_id><content_type>upf</content_type><content_uri><![CDATA[https://commons.ssu.ac.kr/contents29/ssu1000001/60b9b2d69431f/contents/web_files]]></content_uri><content_duration>3355.67</content_duration><content_thumbnail_uri>https://commons.ssu.ac.kr/contents29/ssu1000001/60b9b2d69431f/contents/web_files/slides/thumbnails/big_thumbnail.png</content_thumbnail_uri><story_list><story id="(f8baafa0-02f2-4625-9c46-646dc8a4d6a2)"><story_type>syncvideo</story_type><story_duration>3355.67</story_duration><main_media_list default_media_id="basic_(f8baafa0-02f2-4625-9c46-646dc8a4d6a2)"><main_media media_id="basic_(f8baafa0-02f2-4625-9c46-646dc8a4d6a2)">main_(f8baafa0-02f2-4625-9c46-646dc8a4d6a2).mp4</main_media></main_media_list></story></story_list></content_playing_info><content_metadata version="1.0"><title><![CDATA[11 The World Wide Web (Part 2)]]></title></content_metadata><service_root version="1.0"><web>https://commons.ssu.ac.kr/contents29/ssu1000001/60b9b2d69431f/contents/web_files</web><media type="default"><media_uri method="progressive" target="all">https://ssu-toast.commonscdn.com/contents29/ssu1000001/60b9b2d69431f/contents/media_files/[MEDIA_FILE]</media_uri></media></service_root></content>
            """;

    private WireMockServer wireMockServer;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
        configureFor("localhost", wireMockServer.port());
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void fetchContentInfoParsesCommonsXml() {
        stubFor(get(urlEqualTo("/content.php?content_id=60b9b2d69431f"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/xml")
                        .withBody(SAMPLE_XML)));

        LmsVideoProperties properties = new LmsVideoProperties();
        properties.setCommonsContentPhpUrl("http://localhost:" + wireMockServer.port() + "/content.php");
        CommonsContentClient client = new CommonsContentClient(properties);

        ContentInfo info = client.fetchContentInfo("60b9b2d69431f");

        assertThat(info.contentId()).isEqualTo("60b9b2d69431f");
        assertThat(info.title()).isEqualTo("11 The World Wide Web (Part 2)");
        assertThat(info.storyGuid()).isEqualTo("f8baafa0-02f2-4625-9c46-646dc8a4d6a2");
        assertThat(info.mp4Filename()).isEqualTo("main_(f8baafa0-02f2-4625-9c46-646dc8a4d6a2).mp4");
        assertThat(info.mediaUriTemplate())
                .isEqualTo("https://ssu-toast.commonscdn.com/contents29/ssu1000001/60b9b2d69431f/contents/media_files/[MEDIA_FILE]");
        assertThat(info.webFilesUrl())
                .isEqualTo("https://commons.ssu.ac.kr/contents29/ssu1000001/60b9b2d69431f/contents/web_files");
        assertThat(info.durationSeconds()).isEqualTo(3356);
        assertThat(info.mp4Url())
                .isEqualTo("https://ssu-toast.commonscdn.com/contents29/ssu1000001/60b9b2d69431f/contents/media_files/main_(f8baafa0-02f2-4625-9c46-646dc8a4d6a2).mp4");
    }
}
