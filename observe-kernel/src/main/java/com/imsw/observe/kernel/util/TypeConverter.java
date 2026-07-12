package com.imsw.observe.kernel.util;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public final class TypeConverter {

    private static final Map<Class<?>, Function<Object, ?>> CONVERTERS = new HashMap<>();

    static {
        CONVERTERS.put(String.class, TypeConverter::toStringValue);
        CONVERTERS.put(BigDecimal.class, TypeConverter::toDecimal);
        CONVERTERS.put(Long.class, TypeConverter::toLong);
        CONVERTERS.put(Long.TYPE, TypeConverter::toLong);
        CONVERTERS.put(Integer.class, TypeConverter::toInteger);
        CONVERTERS.put(Integer.TYPE, TypeConverter::toInteger);
        CONVERTERS.put(Instant.class, TypeConverter::toInstant);
    }

    private TypeConverter() {}

    public static BigDecimal toDecimal(final Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value instanceof String text) {
            return new BigDecimal(text.trim());
        }
        throw new IllegalArgumentException("Cannot convert " + value.getClass() + " to BigDecimal");
    }

    public static Long toLong(final Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Long longValue) {
            return longValue;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            return Long.parseLong(text.trim());
        }
        throw new IllegalArgumentException("Cannot convert " + value.getClass() + " to long");
    }

    public static Integer toInteger(final Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Integer integer) {
            return integer;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            return Integer.parseInt(text.trim());
        }
        throw new IllegalArgumentException("Cannot convert " + value.getClass() + " to int");
    }

    public static Instant toInstant(final Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof Number number) {
            return Instant.ofEpochMilli(number.longValue());
        }
        if (value instanceof String text) {
            return Instant.parse(text);
        }
        throw new IllegalArgumentException("Cannot convert " + value.getClass() + " to Instant");
    }

    public static String toStringValue(final Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        return value.toString();
    }

    @SuppressWarnings("unchecked")
    public static <T> T convert(final Object value, final Class<T> targetType) {
        if (value == null) {
            return null;
        }
        if (targetType.isInstance(value)) {
            return (T) value;
        }
        Function<Object, ?> converter = CONVERTERS.get(targetType);
        if (converter == null) {
            throw new IllegalArgumentException("No converter registered for " + targetType);
        }
        return (T) converter.apply(value);
    }
}
