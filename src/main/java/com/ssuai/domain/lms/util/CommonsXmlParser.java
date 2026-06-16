package com.ssuai.domain.lms.util;

import java.io.StringReader;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

public final class CommonsXmlParser {

    private CommonsXmlParser() {
    }

    public static ParsedContent parse(String xml) {
        if (xml == null || xml.isBlank()) {
            return null;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            
            Document doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            String downloadUri = getFirstElementText(doc, "content_download_uri");
            String title = getFirstElementText(doc, "title");
            
            if (downloadUri != null) {
                // DOM parser unescapes &amp; automatically, but let's do a defensive replacement just in case
                downloadUri = downloadUri.replace("&amp;", "&");
            }
            
            return new ParsedContent(title, downloadUri);
        } catch (Exception e) {
            return null;
        }
    }

    private static String getFirstElementText(Document doc, String tagName) {
        NodeList nodes = doc.getElementsByTagName(tagName);
        if (nodes.getLength() > 0) {
            String text = nodes.item(0).getTextContent();
            if (text != null) {
                return text.trim();
            }
        }
        return null;
    }

    public record ParsedContent(String title, String downloadUri) {}
}
