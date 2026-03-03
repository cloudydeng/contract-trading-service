package com.matching.contract.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "account_balance")
@IdClass(AccountBalanceEntity.Pk.class)
public class AccountBalanceEntity {

    @Id
    @Column(nullable = false)
    private Long userId;

    @Id
    @Column(nullable = false, length = 16)
    private String asset;

    @Column(nullable = false)
    private BigDecimal available;

    @Column(nullable = false)
    private BigDecimal frozen;

    @Column(nullable = false)
    private Instant updatedAt;

    @Getter
    @Setter
    @EqualsAndHashCode
    public static class Pk implements Serializable {
        private Long userId;
        private String asset;
    }
}
