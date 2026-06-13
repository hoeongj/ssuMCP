package com.ssuai.domain.lms.video.util;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import org.springframework.stereotype.Component;

@Component
public class CaptionXmlParser {

    /**
     * Parses Korean LMS caption XML variants and returns plain text.
     */
    public String parse(String xml) {
        if (xml == null || xml.isBlank()) {
            return "";
        }
        try {
            Document doc = newDocument(xml);
            List<String> parts = new ArrayList<>();
            collectByTag(doc, parts, "text");
            collectByTag(doc, parts, "Text");
            collectByTag(doc, parts, "CaptionText");
            collectByTag(doc, parts, "p");
            collectCaptionLikeDirectText(doc, parts);
            return String.join(" ", parts).trim();
        } catch (Exception exception) {
            return "";
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

    private static void collectByTag(Document doc, List<String> parts, String tagName) {
        NodeList nodes = doc.getElementsByTagName(tagName);
        for (int i = 0; i < nodes.getLength(); i++) {
            addClean(parts, nodes.item(i).getTextContent());
        }
    }

    private static void collectCaptionLikeDirectText(Document doc, List<String> parts) {
        NodeList nodes = doc.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element
                    && element.getTagName().toLowerCase().contains("caption")) {
                addClean(parts, directText(element));
            }
        }
    }

    private static String directText(Element element) {
        StringBuilder builder = new StringBuilder();
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                builder.append(child.getTextContent()).append(' ');
            }
        }
        return builder.toString();
    }

    private static void addClean(List<String> parts, String raw) {
        String cleaned = clean(raw);
        if (!cleaned.isBlank()) {
            parts.add(cleaned);
        }
    }

    private static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("<[^>]+>", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
