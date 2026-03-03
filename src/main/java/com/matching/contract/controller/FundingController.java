package com.matching.contract.controller;

import com.matching.contract.dto.FundingRateResponse;
import com.matching.contract.dto.FundingSettlementResponse;
import com.matching.contract.service.FundingService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/funding")
public class FundingController {

    private final FundingService fundingService;

    public FundingController(FundingService fundingService) {
        this.fundingService = fundingService;
    }

    @PostMapping("/{symbol}/settle-now")
    public FundingRateResponse settleNow(@PathVariable String symbol) {
        return fundingService.settleNow(symbol);
    }

    @GetMapping("/{symbol}/rates")
    public List<FundingRateResponse> rates(@PathVariable String symbol) {
        return fundingService.recentRates(symbol);
    }

    @GetMapping("/{symbol}/settlements")
    public List<FundingSettlementResponse> settlements(@PathVariable String symbol) {
        return fundingService.recentSettlements(symbol);
    }
}
