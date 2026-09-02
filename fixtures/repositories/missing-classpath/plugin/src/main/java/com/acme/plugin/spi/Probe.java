package com.acme.plugin.spi;

import com.thirdparty.Missing;

/**
 * Fixture: quatro desfechos em um arquivo só.
 *
 * `Registry` sem import nenhum resolve pelo nome simples, que casa com exatamente um
 * tipo interno. `Config` é o caso ambíguo: casa com dois, e o resultado é
 * UNRESOLVED_EXTERNAL marcado como ambíguo, nunca um palpite. `String` é
 * RESOLVED_EXTERNAL. `Missing` é externo de verdade. E `T` não é dependência nenhuma:
 * variável de tipo nunca é.
 */
public final class Probe {

    private Registry registry;

    private Config config;

    private String label;

    private Missing missing;

    public Probe() {
    }

    public <T> void accept(T value) {
    }
}
