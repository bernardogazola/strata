package com.x.orders;

import com.x.orders.dto.OrderDto;

/**
 * Fixture: metade do ciclo de pacote. Um pacote que depende do seu subpacote é uma
 * aresta só; o ciclo precisa da volta, que é o DTO que conhece o agregado.
 */
public final class Order {

    private final OrderDto dto;

    public Order(OrderDto dto) {
        this.dto = dto;
    }

    public void touch() {
    }
}
