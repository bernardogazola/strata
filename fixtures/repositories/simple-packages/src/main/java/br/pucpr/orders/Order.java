package br.pucpr.orders;

import java.util.UUID;

/**
 * Fixture: tipo aninhado nomeado é elemento e usa `$` no id, o que o desambigua de um
 * pacote chamado `Order`.
 */
public final class Order {

    private final UUID id;

    public Order(UUID id) {
        this.id = id;
    }

    public UUID id() {
        return id;
    }

    /** Fixture: `type:br.pucpr.orders.Order$Builder`. */
    public static final class Builder {

        private UUID id;

        public Builder() {
        }

        public Builder id(UUID value) {
            this.id = value;
            return this;
        }

        public Order build() {
            return new Order(id);
        }
    }
}
