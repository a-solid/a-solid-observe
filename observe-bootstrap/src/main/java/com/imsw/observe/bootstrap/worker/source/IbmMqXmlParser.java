package com.imsw.observe.bootstrap.worker.source;

import java.io.StringReader;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.jms.JMSException;
import javax.jms.TextMessage;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import com.imsw.observe.kernel.error.MessageParseException;
import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.kernel.event.model.Op;
import com.imsw.observe.kernel.event.model.SourceType;
import com.imsw.observe.pipeline.infrastructure.source.MessageParser;

/**
 * IBM MQ XML 消息解析器。示例映射（真实规则由用户自行实现/调整）。
 *
 * 约定 XML:
 *
 * <pre>{@code
 * <event>
 *   <source>..</source>      必填
 *   <table>..</table>        可选
 *   <op>CREATE|UPDATE|DELETE|..</op>  可选, 默认 INSERT; CREATE 归一化为 INSERT
 *   <before><k>v</k>..</before>       可选
 *   <after><k>v</k>..</after>         可选
 * </event>
 * }</pre>
 */
public final class IbmMqXmlParser implements MessageParser<TextMessage> {

    private final DocumentBuilder documentBuilder;

    public IbmMqXmlParser() {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            this.documentBuilder = f.newDocumentBuilder();
        } catch (Exception e) {
            throw new IllegalStateException("cannot init DocumentBuilder", e);
        }
    }

    @Override
    public Event parse(final TextMessage raw) throws MessageParseException {
        String xml;
        try {
            xml = raw.getText();
        } catch (JMSException e) {
            throw new MessageParseException("cannot read text from JMS message", e);
        }
        if (xml == null) {
            throw new MessageParseException("null JMS text");
        }
        Document doc;
        try {
            doc = documentBuilder.parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new MessageParseException("malformed XML", e);
        }

        Element root = doc.getDocumentElement();
        String source = childText(root, "source");
        if (source == null || source.isBlank()) {
            throw new MessageParseException("missing <source>");
        }
        String table = childText(root, "table");
        Op op = parseOp(childText(root, "op"));
        Map<String, Object> before = childMap(root, "before");
        Map<String, Object> after = childMap(root, "after");

        Event.EventMeta meta = new Event.EventMeta(SourceType.CDC, source, null, table, Map.of());
        return new Event(meta, before, after, op, Instant.now());
    }

    private static Op parseOp(final String raw) throws MessageParseException {
        if (raw == null || raw.isBlank()) {
            return Op.INSERT;
        }
        String upper = raw.trim().toUpperCase();
        if ("CREATE".equals(upper)) {
            return Op.INSERT;
        }
        try {
            return Op.valueOf(upper);
        } catch (IllegalArgumentException e) {
            throw new MessageParseException("unknown <op>: " + raw.trim(), e);
        }
    }

    private static String childText(final Element parent, final String name) {
        NodeList list = parent.getElementsByTagName(name);
        if (list.getLength() == 0) {
            return null;
        }
        return list.item(0).getTextContent();
    }

    private static Map<String, Object> childMap(final Element parent, final String name) {
        NodeList list = parent.getElementsByTagName(name);
        if (list.getLength() == 0) {
            return Map.of();
        }
        Element container = (Element) list.item(0);
        Map<String, Object> map = new LinkedHashMap<>();
        NodeList children = container.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                map.put(n.getNodeName(), n.getTextContent());
            }
        }
        return Map.copyOf(map);
    }
}
