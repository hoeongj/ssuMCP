package com.ssuai.domain.lms.video.util;

import java.io.StringReader;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.ssuai.domain.lms.video.dto.ContentInfo;
import com.ssuai.domain.lms.video.properties.LmsVideoProperties;
import com.ssuai.global.exception.ConnectorUnavailableException;

@Component
public class CommonsContentClient {

    private final LmsVideoProperties properties;
    private final RestClient restClient;

    public CommonsContentClient(LmsVideoProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .baseUrl(properties.getCommonsContentPhpUrl())
                .build();
    }

    /**
     * Calls Commons content.php and parses story/media metadata for transcript lookup.
     */
    public ContentInfo fetchContentInfo(String contentId) {
        try {
            String xml = restClient.get()
                    .uri(uriBuilder -> uriBuilder.queryParam("content_id", contentId).build())
                    .retrieve()
                    .body(String.class);
            return parseContentInfo(contentId, xml);
        } catch (RestClientResponseException exception) {
            throw unavailable("lms video content fetch failed: status=" + exception.getStatusCode().value(), exception);
        } catch (ConnectorUnavailableException exception) {
            throw exception;
        } catch (Exception exception) {
            throw unavailable("lms video content parse failed: " + exception.getMessage(), exception);
        }
    }

    ContentInfo parseContentInfo(String requestedContentId, String xml) {
        if (xml == null || xml.isBlank()) {
            throw unavailable("lms video content not found: " + requestedContentId);
        }
        try {
            Document doc = newDocument(xml);
            Element story = firstElement(doc, "story");
            if (story == null) {
                throw unavailable("lms video content not found: " + requestedContentId);
            }

            String contentId = firstText(doc, "content_id", requestedContentId);
            String title = firstText(doc, "title", "");
            String storyGuid = stripParens(story.getAttribute("id"));
            String mp4Filename = firstText(doc, "main_media", "");
            String mediaUriTemplate = progressiveMediaUri(doc);
            String webFilesUrl = firstText(doc, "web", firstText(doc, "content_uri", ""));
            int durationSeconds = durationSeconds(firstText(doc, "content_duration",
                    firstText(doc, "story_duration", "0")));

            if (storyGuid.isBlank() || mp4Filename.isBlank()
                    || mediaUriTemplate.isBlank() || webFilesUrl.isBlank()) {
                throw unavailable("lms video content metadata incomplete: " + requestedContentId);
            }
            return new ContentInfo(contentId, title, storyGuid, mp4Filename,
                    mediaUriTemplate, webFilesUrl, durationSeconds);
        } catch (ConnectorUnavailableException exception) {
            throw exception;
        } catch (Exception exception) {
            throw unavailable("lms video content XML parse failed: " + exception.getMessage(), exception);
        }
    }

    private static Document newDocument(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private static String progressiveMediaUri(Document doc) {
        NodeList nodes = doc.getElementsByTagName("media_uri");
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element
                    && "progressive".equalsIgnoreCase(element.getAttribute("method"))
                    && "all".equalsIgnoreCase(element.getAttribute("target"))) {
                return normalized(element.getTextContent());
            }
        }
        return "";
    }

    private static Element firstElement(Document doc, String tagName) {
        NodeList nodes = doc.getElementsByTagName(tagName);
        if (nodes.getLength() == 0 || !(nodes.item(0) instanceof Element element)) {
            return null;
        }
        return element;
    }

    private static String firstText(Document doc, String tagName, String fallback) {
        Element element = firstElement(doc, tagName);
        if (element == null) {
            return fallback;
        }
        String value = normalized(element.getTextContent());
        return value.isBlank() ? fallback : value;
    }

    private static int durationSeconds(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return (int) Math.round(Double.parseDouble(value.trim()));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static String stripParens(String value) {
        String result = normalized(value);
        if (result.startsWith("(") && result.endsWith(")") && result.length() > 2) {
            return result.substring(1, result.length() - 1);
        }
        return result;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static ConnectorUnavailableException unavailable(String message) {
        return unavailable(message, null);
    }

    private static ConnectorUnavailableException unavailable(String message, Throwable cause) {
        return new ConnectorUnavailableException(new IllegalStateException(message, cause));
    }
}
