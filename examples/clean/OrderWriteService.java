package com.example.orders;

import org.springframework.transaction.annotation.Transactional;

/**
 * Plain @Transactional (readOnly defaults to false) doing real writes --
 * this is the normal, expected case and must never be flagged: no
 * readOnly = true claim is being made here at all.
 */
public class OrderWriteService {

    private final OrderRepository orderRepository;

    public OrderWriteService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Transactional
    public Order place(Order order) {
        return orderRepository.save(order);
    }
}
