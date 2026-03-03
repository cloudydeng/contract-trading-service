package com.matching.contract.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "price")
public class PriceEngineProperties {

    private int markBasisLimitBps = 30;
    private long sampleIntervalMs = 5000;
    private int settlementWindowSeconds = 60;

    public int getMarkBasisLimitBps() {
        return markBasisLimitBps;
    }

    public void setMarkBasisLimitBps(int markBasisLimitBps) {
        this.markBasisLimitBps = markBasisLimitBps;
    }

    public long getSampleIntervalMs() {
        return sampleIntervalMs;
    }

    public void setSampleIntervalMs(long sampleIntervalMs) {
        this.sampleIntervalMs = sampleIntervalMs;
    }

    public int getSettlementWindowSeconds() {
        return settlementWindowSeconds;
    }

    public void setSettlementWindowSeconds(int settlementWindowSeconds) {
        this.settlementWindowSeconds = settlementWindowSeconds;
    }
}
