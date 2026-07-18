package com.imsw.observe.pipeline.domain.subscription;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.kernel.event.model.EventPaths;

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

        /**
         * 路径解析统一委托 {@link EventPaths#get}（kernel 唯一实现，避免重复）。
         *
         * <p>fieldFilter 主要对 CDC 订阅有意义（before/after/op/meta.db/table）；
         * 对 {@link com.imsw.observe.kernel.event.model.TickEvent} /
         * {@link com.imsw.observe.kernel.event.model.ApiEvent} /
         * {@link com.imsw.observe.kernel.event.model.DelayedEvent}，按对应子类型的可解析路径
         * （payload/meta.apiName/meta.cronName/meta.attributes 等）返回，CDC 专属路径返回 null。
         */
        static Object resolve(final Event event, final String path) {
            return EventPaths.get(event, path);
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
