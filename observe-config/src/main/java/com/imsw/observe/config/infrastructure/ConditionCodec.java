package com.imsw.observe.config.infrastructure;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.imsw.observe.kernel.util.JsonUtil;
import com.imsw.observe.pipeline.domain.subscription.Condition;

public final class ConditionCodec {

    private final ObjectMapper objectMapper;

    public ConditionCodec() {
        this.objectMapper = JsonUtil.mapper().copy();
        SimpleModule module = new SimpleModule();
        module.addSerializer(Condition.class, new ConditionSerializer());
        module.addDeserializer(Condition.class, new ConditionDeserializer());
        objectMapper.registerModule(module);
    }

    public String toJson(final Condition condition) {
        if (condition == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(condition);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize Condition", e);
        }
    }

    public Condition fromJson(final String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Condition.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to deserialize Condition: " + json, e);
        }
    }

    private static final class ConditionSerializer extends StdSerializer<Condition> {

        ConditionSerializer() {
            super(Condition.class);
        }

        @Override
        public void serialize(final Condition value, final JsonGenerator gen, final SerializerProvider provider)
                throws IOException {
            writeCondition(value, gen);
        }

        private void writeCondition(final Condition value, final JsonGenerator gen) throws IOException {
            if (value instanceof Condition.And and) {
                writeComposite("And", and.children(), gen);
            } else if (value instanceof Condition.Or or) {
                writeComposite("Or", or.children(), gen);
            } else if (value instanceof Condition.Compare compare) {
                writeCompare(compare, gen);
            } else if (value instanceof Condition.In in) {
                writeIn(in, gen);
            }
        }

        private void writeComposite(final String type, final List<Condition> children, final JsonGenerator gen)
                throws IOException {
            gen.writeStartObject();
            gen.writeStringField("type", type);
            gen.writeFieldName("children");
            gen.writeStartArray();
            for (Condition child : children) {
                writeCondition(child, gen);
            }
            gen.writeEndArray();
            gen.writeEndObject();
        }

        private void writeCompare(final Condition.Compare compare, final JsonGenerator gen) throws IOException {
            gen.writeStartObject();
            gen.writeStringField("type", "Compare");
            gen.writeStringField("field", compare.field());
            gen.writeStringField("op", compare.op().name());
            gen.writeObjectField("value", compare.value());
            gen.writeEndObject();
        }

        private void writeIn(final Condition.In in, final JsonGenerator gen) throws IOException {
            gen.writeStartObject();
            gen.writeStringField("type", "In");
            gen.writeStringField("field", in.field());
            gen.writeFieldName("values");
            gen.writeStartArray();
            if (in.values() != null) {
                for (Object v : in.values()) {
                    gen.writeObject(v);
                }
            }
            gen.writeEndArray();
            gen.writeEndObject();
        }
    }

    private static final class ConditionDeserializer extends StdDeserializer<Condition> {

        ConditionDeserializer() {
            super(Condition.class);
        }

        @Override
        public Condition deserialize(final JsonParser p, final DeserializationContext ctxt) throws IOException {
            JsonNode node = p.getCodec().readTree(p);
            return readCondition(node);
        }

        private Condition readCondition(final JsonNode node) {
            String type = node.get("type").asText();
            return switch (type) {
                case "And" -> new Condition.And(readChildren(node.get("children")));
                case "Or" -> new Condition.Or(readChildren(node.get("children")));
                case "Compare" -> new Condition.Compare(
                        node.get("field").asText(),
                        Condition.Compare.Op.valueOf(node.get("op").asText()),
                        readScalar(node.get("value")));
                case "In" -> new Condition.In(node.get("field").asText(), readValueSet(node.get("values")));
                default -> throw new IllegalStateException("Unknown Condition type: " + type);
            };
        }

        private List<Condition> readChildren(final JsonNode array) {
            List<Condition> children = new ArrayList<>();
            if (array != null) {
                for (JsonNode child : array) {
                    children.add(readCondition(child));
                }
            }
            return children;
        }

        private Object readScalar(final JsonNode node) {
            if (node == null || node.isNull()) {
                return null;
            }
            if (node.isNumber()) {
                return node.decimalValue();
            }
            if (node.isBoolean()) {
                return node.asBoolean();
            }
            return node.asText();
        }

        private Set<Object> readValueSet(final JsonNode array) {
            Set<Object> values = new LinkedHashSet<>();
            if (array != null) {
                for (JsonNode element : array) {
                    values.add(readScalar(element));
                }
            }
            return values;
        }
    }
}
