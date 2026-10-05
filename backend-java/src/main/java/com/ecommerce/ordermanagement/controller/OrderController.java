package com.ecommerce.ordermanagement.controller;

import com.ecommerce.ordermanagement.model.OrderDtos.OrderCreate;
import com.ecommerce.ordermanagement.model.OrderDtos.OrderDetail;
import com.ecommerce.ordermanagement.model.OrderDtos.OrderItemOut;
import com.ecommerce.ordermanagement.model.OrderDtos.OrderOut;
import com.ecommerce.ordermanagement.model.OrderDtos.StatusUpdate;
import com.ecommerce.ordermanagement.repository.OrderRepository;
import com.ecommerce.ordermanagement.service.OrderService;
import com.ecommerce.ordermanagement.web.ApiException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Order routes. PostgreSQL is the source of truth. */
@RestController
public class OrderController {

    private final OrderService orderService;
    private final OrderRepository orderRepository;

    public OrderController(OrderService orderService, OrderRepository orderRepository) {
        this.orderService = orderService;
        this.orderRepository = orderRepository;
    }

    @PostMapping("/api/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderOut createOrder(@Valid @RequestBody OrderCreate req) {
        return orderService.placeOrder(req);
    }

    @GetMapping("/api/orders/{orderId}")
    public OrderDetail getOrder(@PathVariable long orderId) {
        var row = orderRepository.fetchOrderRow(orderId);
        if (row == null) {
            throw ApiException.notFound("order " + orderId + " not found");
        }
        List<OrderItemOut> items = orderRepository.fetchOrderItems(orderId).stream()
                .map(i -> new OrderItemOut(i.productId(), i.title(), i.quantity(), i.unitPrice()))
                .toList();
        return new OrderDetail(
                row.id(),
                row.userId(),
                row.customerName(),
                row.customerEmail(),
                row.orderDate(),
                row.updatedAt(),
                row.status(),
                row.totalAmount(),
                row.version(),
                items);
    }

    @PatchMapping("/api/orders/{orderId}/status")
    public OrderOut patchStatus(@PathVariable long orderId, @Valid @RequestBody StatusUpdate req) {
        return orderService.updateStatus(orderId, req.status(), req.expected_version());
    }
}