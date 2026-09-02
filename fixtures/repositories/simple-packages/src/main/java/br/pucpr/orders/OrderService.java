package br.pucpr.orders;

import java.util.List;
import java.util.UUID;

/**
 * Fixture: a classe anônima não é elemento, e o seu `run()` pertence ao tipo nomeado mais
 * próximo; `List<String>` apaga para `java.util.List`; e `T extends Order` apaga para o
 * primeiro limite.
 */
public final class OrderService {

    private final List<Order> orders;

    public OrderService(List<Order> orders) {
        this.orders = orders;
    }

    public void register(List<String> labels) {
    }

    public <T extends Order> T first(T candidate) {
        return candidate;
    }

    public Runnable auditor() {
        return new Runnable() {

            public void run() {
                Log.info("audit");
            }
        };
    }

    public Order build(UUID id) {
        return new Order.Builder().id(id).build();
    }
}
