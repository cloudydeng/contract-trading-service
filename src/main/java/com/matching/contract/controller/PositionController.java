package com.matching.contract.controller;

import com.matching.contract.dto.ClosePositionMarketRequest;
import com.matching.contract.dto.ClosePositionResponse;
import com.matching.contract.service.PositionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/positions")
public class PositionController {

    private final PositionService positionService;

    public PositionController(PositionService positionService) {
        this.positionService = positionService;
    }

    @PostMapping("/{symbol}/close-market")
    public ClosePositionResponse closeMarket(@PathVariable String symbol,
                                             @Valid @RequestBody ClosePositionMarketRequest request) {
        return positionService.closeMarket(symbol, request);
    }
}
