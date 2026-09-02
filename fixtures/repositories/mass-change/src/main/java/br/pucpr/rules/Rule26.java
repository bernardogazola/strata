package br.pucpr.rules;

/**
 * Fixture: classificação de commit em massa. Uma das trinta regras que o commit de
 * reformatação toca de uma vez.
 *
 * <p>REVISION é a contagem de commits que tocaram este arquivo.
 */
public final class Rule26 {

    static final int REVISION = 2;

    Rule26() {
    }

    int revision() {
        return REVISION;
    }
}
