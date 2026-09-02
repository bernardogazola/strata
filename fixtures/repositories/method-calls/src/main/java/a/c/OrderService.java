package a.c;

import a.b.*;
import java.util.List;

/**
 * Fixture: cinco pontos de chamada e duas declarações de campo para OrderRepository,
 * com peso agregado 5*2 + 2*3 = 16. Os campos não são
 * inicializados de propósito: um `new OrderRepository()` acrescentaria um ponto de
 * chamada e a conta deixaria de dar 16.
 *
 * O `import a.b.*;` não nomeia tipo nenhum e não produz aresta. `List<Order>` desembrulha
 * recursivamente e produz as duas metades: `java.util.List`, externa, e `a.b.Order`,
 * interna.
 */
public final class OrderService {

    private OrderRepository primary;
    private OrderRepository fallback;
    private final List<Order> cache;

    public OrderService(List<Order> cache) {
        this.cache = cache;
    }

    public void refresh() {
        primary.reload();
        fallback.reload();
    }

    public Task auditor() {
        return () -> primary.audit();
    }

    public Task reporter() {
        return new Task() {

            public void run() {
                fallback.audit();
            }
        };
    }

    public void seed() {
        primary.first();
    }
}
