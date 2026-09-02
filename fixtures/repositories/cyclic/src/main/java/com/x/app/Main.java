package com.x.app;

import com.x.orders.Order;

/**
 * Fixture: aponta para dentro do ciclo, mas não faz parte dele. Serve para pegar uma
 * implementação que chame "alcançável a partir de um ciclo" de ciclo.
 */
public final class Main {

    private final Order order;

    public Main(Order order) {
        this.order = order;
    }

    public void run() {
        order.touch();
    }
}
