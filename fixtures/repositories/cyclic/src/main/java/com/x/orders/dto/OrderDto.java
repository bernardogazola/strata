package com.x.orders.dto;

import com.x.orders.Order;

public final class OrderDto {

    private Order order;

    public OrderDto() {
    }

    public void attach(Order value) {
        this.order = value;
    }
}
