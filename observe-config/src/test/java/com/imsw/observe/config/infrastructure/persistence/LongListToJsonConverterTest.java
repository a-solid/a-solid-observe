package com.imsw.observe.config.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class LongListToJsonConverterTest {

    private final LongListToJsonConverter converter = new LongListToJsonConverter();

    @Test
    void roundTripsListOfLongs() {
        List<Long> ids = List.of(1001L, 1002L, 1003L);
        String json = converter.convertToDatabaseColumn(ids);
        assertThat(json).isEqualTo("[1001,1002,1003]");
        assertThat(converter.convertToEntityAttribute(json)).containsExactly(1001L, 1002L, 1003L);
    }

    @Test
    void nullAndEmptyMapToNullColumn() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToDatabaseColumn(List.of())).isNull();
    }

    @Test
    void nullAndBlankColumnMapToEmptyList() {
        assertThat(converter.convertToEntityAttribute(null)).isEmpty();
        assertThat(converter.convertToEntityAttribute("  ")).isEmpty();
    }
}
