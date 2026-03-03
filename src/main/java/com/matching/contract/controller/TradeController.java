package com.matching.contract.controller;

import com.matching.contract.dto.TradeResponse;
import com.matching.contract.service.TradeQueryService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/trades")
public class TradeController {

    private final TradeQueryService tradeQueryService;

    public TradeController(TradeQueryService tradeQueryService) {
        this.tradeQueryService = tradeQueryService;
    }

    @GetMapping
    public List<TradeResponse> queryTrades(@RequestParam(required = false) Long userId,
                                           @RequestParam(required = false) Long orderId) {
        return tradeQueryService.query(userId, orderId);
    }
}
