package com.matching.contract.service;

import com.matching.contract.dto.TradeResponse;
import com.matching.contract.exception.BadRequestException;
import com.matching.contract.repository.TradeRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TradeQueryService {

    private final TradeRepository tradeRepository;

    public TradeQueryService(TradeRepository tradeRepository) {
        this.tradeRepository = tradeRepository;
    }

    @Transactional(readOnly = true)
    public List<TradeResponse> query(Long userId, Long orderId) {
        if (userId == null && orderId == null) {
            throw new BadRequestException("either userId or orderId is required");
        }
        if (userId != null && orderId != null) {
            return tradeRepository.findByUserIdAndOrderIdOrderByCreatedAtDesc(userId, orderId)
                    .stream()
                    .map(TradeResponse::from)
                    .toList();
        }
        if (userId != null) {
            return tradeRepository.findByUserIdOrderByCreatedAtDesc(userId)
                    .stream()
                    .map(TradeResponse::from)
                    .toList();
        }
        return tradeRepository.findByOrderIdOrderByCreatedAtDesc(orderId)
                .stream()
                .map(TradeResponse::from)
                .toList();
    }
}
