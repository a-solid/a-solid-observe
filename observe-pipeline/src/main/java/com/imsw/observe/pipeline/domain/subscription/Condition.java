package com.imsw.observe.pipeline.domain.subscription;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.imsw.observe.kernel.event.model.Event;

public sealed interface Condition permits Condition.And, Condition.Or, Condition.Compare, Condition.In {

    boolean matches(Event event);

    record And(List<Condition> children) implements Condition {
        @Override
        public boolean matches(final Event event) {
            for (Condition c : children) {
                if (!c.matches(event)) {
                    return false;
                }
            }
            return true;
        }
    }

    record Or(List<Condition> children) implements Condition {
        @Override
        public boolean matches(final Event event) {
            for (Condition c : children) {
                if (c.matches(event)) {
                    return true;
                }
            }
            return false;
        }
    }

    record Compare(String field, Op op, Object value) implements Condition {
        public enum Op {
            EQ,
            NE,
            GT,
            GE,
            LT,
            LE
        }

        @Override
        public boolean matches(final Event event) {
            return FieldOps.compare(Fields.resolve(event, field), op, value);
        }
    }

    record In(String field, Set<Object> values) implements Condition {
        @Override
        public boolean matches(final Event event) {
            return FieldOps.in(Fields.resolve(event, field), values);
        }
    }

    final class Fields {

        private Fields() {}

        static Object resolve(final Event event, final String path) {
            if (path == null || path.isEmpty()) {
                return null;
            }
            if ("op".equals(path)) {
                return event.op() == null ? null : event.op().name();
            }
            if (path.startsWith("before.")) {
                return event.before() == null ? null : event.before().get(path.substring("before.".length()));
            }
            if (path.startsWith("after.")) {
                return event.after() == null ? null : event.after().get(path.substring("after.".length()));
            }
            if (path.startsWith("meta.")) {
                return resolveMeta(event, path.substring("meta.".length()));
            }
            return null;
        }

        private static Object resolveMeta(final Event event, final String rest) {
            Event.EventMeta meta = event.meta();
            if (meta == null) {
                return null;
            }
            if (rest.equals("db")) {
                return meta.db();
            }
            if (rest.equals("table")) {
                return meta.table();
            }
            if (rest.equals("source")) {
                return meta.source();
            }
            if (rest.startsWith("attributes.")) {
                Map<String, Object> attrs = meta.attributes();
                return attrs == null ? null : attrs.get(rest.substring("attributes.".length()));
            }
            return null;
        }
    }

    final class FieldOps {

        private FieldOps() {}

        static boolean compare(final Object actual, final Compare.Op op, final Object expected) {
            if (op == Compare.Op.EQ) {
                return equalsNormalized(actual, expected);
            }
            if (op == Compare.Op.NE) {
                return !equalsNormalized(actual, expected);
            }
            int cmp = compareOrdered(actual, expected);
            if (cmp == Integer.MIN_VALUE) {
                return false;
            }
            return switch (op) {
                case GT -> cmp > 0;
                case GE -> cmp >= 0;
                case LT -> cmp < 0;
                case LE -> cmp <= 0;
                default -> false;
            };
        }

        static boolean in(final Object actual, final Set<Object> values) {
            if (values == null || values.isEmpty()) {
                return false;
            }
            for (Object candidate : values) {
                if (equalsNormalized(actual, candidate)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean equalsNormalized(final Object actual, final Object expected) {
            if (actual == null || expected == null) {
                return actual == null && expectedIsNull(expected);
            }
            BigDecimal a = asDecimal(actual);
            BigDecimal b = asDecimal(expected);
            if (a != null && b != null) {
                return a.compareTo(b) == 0;
            }
            return actual.toString().equals(expected.toString());
        }

        private static int compareOrdered(final Object actual, final Object expected) {
            if (actual == null || expected == null) {
                return Integer.MIN_VALUE;
            }
            BigDecimal a = asDecimal(actual);
            BigDecimal b = asDecimal(expected);
            if (a != null && b != null) {
                return a.compareTo(b);
            }
            return actual.toString().compareTo(expected.toString());
        }

        private static boolean expectedIsNull(final Object expected) {
            return expected == null || "null".equalsIgnoreCase(asString(expected));
        }

        private static BigDecimal asDecimal(final Object value) {
            if (value instanceof BigDecimal decimal) {
                return decimal;
            }
            if (value instanceof Number number) {
                return BigDecimal.valueOf(number.doubleValue());
            }
            if (value instanceof String text) {
                try {
                    return new BigDecimal(text.trim());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            return null;
        }

        private static String asString(final Object value) {
            return value == null ? null : value.toString();
        }
    }
}
