package com.imsw.observe.config.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.imsw.observe.pipeline.application.PipelineRegistry;

public class ConfigLoader {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigLoader.class);

    private final PipelineRegistry registry;

    private final PipelineRegistryLoader loader;

    public ConfigLoader(final PipelineRegistry registry, final PipelineRegistryLoader loader) {
        this.registry = registry;
        this.loader = loader;
    }

    public void load() {
        LOG.info("cold-start config load begin");
        registry.replace(loader.load());
        LOG.info("cold-start config load done; loaded={}", registry.isLoaded());
    }
}
