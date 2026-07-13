package com.imsw.observe.bootstrap.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "observe.worker.ibm-mq")
public class IbmMqProperties {

    private boolean enabled = false;

    private String host;

    private int port = 1414;

    private String queueManager;

    private String channel;

    private String queue;

    private int batchSize = 50;

    private long batchTimeoutMillis = 200L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
    }

    public String getHost() {
        return host;
    }

    public void setHost(final String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(final int port) {
        this.port = port;
    }

    public String getQueueManager() {
        return queueManager;
    }

    public void setQueueManager(final String queueManager) {
        this.queueManager = queueManager;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(final String channel) {
        this.channel = channel;
    }

    public String getQueue() {
        return queue;
    }

    public void setQueue(final String queue) {
        this.queue = queue;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(final int batchSize) {
        this.batchSize = batchSize;
    }

    public long getBatchTimeoutMillis() {
        return batchTimeoutMillis;
    }

    public void setBatchTimeoutMillis(final long batchTimeoutMillis) {
        this.batchTimeoutMillis = batchTimeoutMillis;
    }
}
