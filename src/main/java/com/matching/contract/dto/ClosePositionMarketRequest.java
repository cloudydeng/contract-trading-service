package com.matching.contract.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record ClosePositionMarketRequest(
        @NotNull Long userId,
        @DecimalMin("0.00000001") BigDecimal quantity,
        @DecimalMin("0.00000001") BigDecimal markPrice
) {
}
