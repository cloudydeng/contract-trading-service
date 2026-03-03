package com.matching.contract.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "liquidation")
public class LiquidationProperties {

    private boolean enabled = true;
    private int scanIntervalMs = 2000;
    private int triggerLossBps = 8000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getScanIntervalMs() {
        return scanIntervalMs;
    }

    public void setScanIntervalMs(int scanIntervalMs) {
        this.scanIntervalMs = scanIntervalMs;
    }

    public int getTriggerLossBps() {
        return triggerLossBps;
    }

    public void setTriggerLossBps(int triggerLossBps) {
        this.triggerLossBps = triggerLossBps;
    }
}
