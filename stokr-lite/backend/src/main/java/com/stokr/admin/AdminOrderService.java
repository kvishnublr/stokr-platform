package com.stokr.admin;

import com.stokr.oms.Order;
import com.stokr.oms.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminOrderService {

    private final OrderRepository orderRepository;

    public Page<Order> getAllOrders(Pageable pageable) {
        return orderRepository.findAll(pageable);
    }

    public List<Order> getPendingOrders() {
        return orderRepository.findByStatus(Order.PENDING);
    }

    public Map<String, Long> getOrderStats() {
        return Map.of(
                "total", orderRepository.count(),
                "pending", (long) orderRepository.findByStatus(Order.PENDING).size(),
                "complete", (long) orderRepository.findByStatus(Order.COMPLETE).size(),
                "rejected", (long) orderRepository.findByStatus(Order.REJECTED).size()
        );
    }
}
