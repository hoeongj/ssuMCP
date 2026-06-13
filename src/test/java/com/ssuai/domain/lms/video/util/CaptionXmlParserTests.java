package com.ssuai.domain.lms.video.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CaptionXmlParserTests {

    private final CaptionXmlParser parser = new CaptionXmlParser();

    @Test
    void parsesLowercaseCaptionTextFormat() {
        String xml = """
                <caption_list><caption><starttime>0.000</starttime><endtime>5.000</endtime><text>강의 내용입니다.</text></caption></caption_list>
                """;

        assertThat(parser.parse(xml)).isEqualTo("강의 내용입니다.");
    }

    @Test
    void parsesUppercaseCaptionTextFormat() {
        String xml = """
                <Captions><Caption Begin="0:00:00.000" End="0:00:05.000"><Text>강의 내용입니다.</Text></Caption></Captions>
                """;

        assertThat(parser.parse(xml)).isEqualTo("강의 내용입니다.");
    }

    @Test
    void returnsEmptyForEmptyOrMalformedInput() {
        assertThat(parser.parse(null)).isEmpty();
        assertThat(parser.parse("")).isEmpty();
        assertThat(parser.parse("<caption_list><caption>")).isEmpty();
    }
}
