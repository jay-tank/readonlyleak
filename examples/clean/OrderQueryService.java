package com.example.orders;

import org.springframework.transaction.annotation.Transactional;

/**
 * readOnly = true used correctly: every method in this class only reads.
 */
public class OrderQueryService {

    private final OrderRepository orderRepository;

    public OrderQueryService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Transactional(readOnly = true)
    public Order lookup(Long id) {
        return orderRepository.findById(id).orElseThrow();
    }

    @Transactional(readOnly = true)
    public java.util.List<Order> listAll() {
        return orderRepository.findAll();
    }
}
