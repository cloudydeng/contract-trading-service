package com.matching.contract;

import com.matching.contract.config.PriceEngineProperties;
import com.matching.contract.config.LiquidationProperties;
import com.matching.contract.config.FundingProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({PriceEngineProperties.class, LiquidationProperties.class, FundingProperties.class})
public class ContractTradingApplication {

    public static void main(String[] args) {
        SpringApplication.run(ContractTradingApplication.class, args);
    }
}
