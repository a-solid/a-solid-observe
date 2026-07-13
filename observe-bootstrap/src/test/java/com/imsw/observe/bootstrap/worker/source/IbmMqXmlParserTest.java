package com.imsw.observe.bootstrap.worker.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import javax.jms.TextMessage;

import org.junit.jupiter.api.Test;

import com.imsw.observe.kernel.error.MessageParseException;
import com.imsw.observe.kernel.event.model.Op;
import com.imsw.observe.kernel.event.model.SourceType;

class IbmMqXmlParserTest {

    private final IbmMqXmlParser parser = new IbmMqXmlParser();

    private static TextMessage textMessage(final String body) throws Exception {
        TextMessage msg = mock(TextMessage.class);
        when(msg.getText()).thenReturn(body);
        return msg;
    }

    @Test
    void parsesFullEvent() throws Exception {
        TextMessage msg = textMessage(
                """
                <event>
                  <source>order-service</source>
                  <table>orders</table>
                  <op>CREATE</op>
                  <after>
                    <id>123</id>
                    <status>PAID</status>
                  </after>
                </event>
                """);

        var event = parser.parse(msg);

        assertEquals(SourceType.CDC, event.meta().sourceType());
        assertEquals("order-service", event.meta().source());
        assertEquals("orders", event.meta().table());
        // 示例映射: "CREATE" 归一化为 Op.INSERT (Op 无 CREATE)
        assertEquals(Op.INSERT, event.op());
        assertEquals("123", event.after().get("id"));
        assertEquals("PAID", event.after().get("status"));
        assertNotNull(event.sourceTs());
    }

    @Test
    void parsesBeforeAndAfter() throws Exception {
        TextMessage msg = textMessage(
                """
                <event>
                  <source>svc</source>
                  <op>UPDATE</op>
                  <before><id>1</id></before>
                  <after><id>1</id><status>OK</status></after>
                </event>
                """);

        var event = parser.parse(msg);

        assertEquals(Op.UPDATE, event.op());
        assertEquals("1", event.before().get("id"));
        assertEquals("OK", event.after().get("status"));
    }

    @Test
    void rejectsMalformedXml() throws Exception {
        TextMessage msg = textMessage("<event><source>svc</source>"); // 未闭合
        assertThrows(MessageParseException.class, () -> parser.parse(msg));
    }

    @Test
    void rejectsMissingSource() throws Exception {
        TextMessage msg = textMessage("<event><op>INSERT</op></event>");
        assertThrows(MessageParseException.class, () -> parser.parse(msg));
    }

    @Test
    void rejectsUnknownOp() throws Exception {
        TextMessage msg = textMessage(
                """
                <event>
                  <source>svc</source>
                  <op>UPSERT</op>
                </event>
                """);
        assertThrows(MessageParseException.class, () -> parser.parse(msg));
    }

    @Test
    void emptyAfterYieldsEmptyMap() throws Exception {
        TextMessage msg = textMessage(
                """
                <event>
                  <source>svc</source>
                  <after></after>
                </event>
                """);

        var event = parser.parse(msg);

        assertEquals(SourceType.CDC, event.meta().sourceType());
        org.junit.jupiter.api.Assertions.assertTrue(event.after().isEmpty());
    }
}
