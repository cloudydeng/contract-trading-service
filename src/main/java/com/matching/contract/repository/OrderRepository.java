package com.matching.contract.repository;

import com.matching.contract.entity.OrderEntity;
import com.matching.contract.entity.OrderStatus;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<OrderEntity, Long> {
    List<OrderEntity> findByStatusIn(Collection<OrderStatus> statuses);
}
