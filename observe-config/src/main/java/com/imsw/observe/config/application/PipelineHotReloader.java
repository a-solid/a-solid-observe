package com.imsw.observe.config.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.imsw.observe.pipeline.application.PipelineRegistry;

public class PipelineHotReloader {

    private static final Logger LOG = LoggerFactory.getLogger(PipelineHotReloader.class);

    private final PipelineRegistry registry;

    private final PipelineRegistryLoader loader;

    public PipelineHotReloader(final PipelineRegistry registry, final PipelineRegistryLoader loader) {
        this.registry = registry;
        this.loader = loader;
    }

    public void refresh() {
        try {
            registry.replace(loader.load());
        } catch (RuntimeException e) {
            LOG.warn("hot reload failed; keeping previous snapshot", e);
        }
    }
}
