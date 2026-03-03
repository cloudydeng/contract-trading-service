package com.matching.contract.repository;

import com.matching.contract.entity.AccountBalanceEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountBalanceRepository extends JpaRepository<AccountBalanceEntity, AccountBalanceEntity.Pk> {
    Optional<AccountBalanceEntity> findByUserIdAndAsset(Long userId, String asset);
}
