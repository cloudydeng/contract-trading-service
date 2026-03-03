package com.matching.contract.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "funding")
public class FundingProperties {

    private boolean enabled = true;
    private int intervalHours = 8;
    private int maxRateBps = 75;
    private int settleDelaySeconds = 10;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getIntervalHours() {
        return intervalHours;
    }

    public void setIntervalHours(int intervalHours) {
        this.intervalHours = intervalHours;
    }

    public int getMaxRateBps() {
        return maxRateBps;
    }

    public void setMaxRateBps(int maxRateBps) {
        this.maxRateBps = maxRateBps;
    }

    public int getSettleDelaySeconds() {
        return settleDelaySeconds;
    }

    public void setSettleDelaySeconds(int settleDelaySeconds) {
        this.settleDelaySeconds = settleDelaySeconds;
    }
}
