package com.matching.contract.controller;

import com.matching.contract.dto.MarkPriceResponse;
import com.matching.contract.dto.SettlementPriceResponse;
import com.matching.contract.dto.UpdateIndexPriceRequest;
import com.matching.contract.price.MarkPriceService;
import com.matching.contract.price.SettlementPriceService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/prices")
public class PriceController {

    private final MarkPriceService markPriceService;
    private final SettlementPriceService settlementPriceService;

    public PriceController(MarkPriceService markPriceService, SettlementPriceService settlementPriceService) {
        this.markPriceService = markPriceService;
        this.settlementPriceService = settlementPriceService;
    }

    @PostMapping("/index")
    public MarkPriceResponse updateIndex(@Valid @RequestBody UpdateIndexPriceRequest request) {
        return markPriceService.updateIndexPrice(request.symbol(), request.sources());
    }

    @GetMapping("/mark/{symbol}")
    public MarkPriceResponse getMark(@PathVariable String symbol) {
        return markPriceService.getMarkPrice(symbol);
    }

    @PostMapping("/settle/{symbol}")
    public SettlementPriceResponse settleNow(@PathVariable String symbol) {
        return settlementPriceService.settleNow(symbol);
    }

    @GetMapping("/settle/{symbol}")
    public List<SettlementPriceResponse> recentSettlements(@PathVariable String symbol,
                                                            @RequestParam(required = false, defaultValue = "20") int limit) {
        List<SettlementPriceResponse> rows = settlementPriceService.recentSettlements(symbol);
        return rows.subList(0, Math.min(limit, rows.size()));
    }
}
