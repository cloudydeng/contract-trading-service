package com.matching.contract.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record PlaceOrderRequest(
        @NotNull Long userId,
        @NotBlank String symbol,
        @NotBlank String side,
        @NotBlank String orderType,
        @NotBlank String timeInForce,
        @NotNull @DecimalMin("0.00000001") BigDecimal quantity,
        @DecimalMin("0.00000001") BigDecimal price,
        boolean reduceOnly
) {
}
