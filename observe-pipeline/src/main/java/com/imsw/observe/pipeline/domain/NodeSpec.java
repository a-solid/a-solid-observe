package com.imsw.observe.pipeline.domain;

import java.util.Set;

public record NodeSpec(
        String name, String scriptSource, ErrorPolicy errorPolicy, Set<String> provides, Set<String> reads) {}
