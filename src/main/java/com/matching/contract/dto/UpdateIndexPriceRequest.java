package com.matching.contract.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.Map;

public record UpdateIndexPriceRequest(
        @NotBlank String symbol,
        @NotEmpty Map<String, @NotNull BigDecimal> sources
) {
}
