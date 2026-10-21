/**
 * Golden de complexidade ciclomática: os desvios simples ({@code if}, o {@code else} que soma
 * zero, a cadeia {@code else if}, o ternário aninhado) e a regra dos operadores: só {@code &&} e
 * {@code ||} contam. {@code &}, {@code |}, {@code ^} e as atribuições compostas {@code &=},
 * {@code |=}, {@code ^=} sobre booleanos avaliam os dois lados e não desviam nada.
 *
 * <p>Sem tipo aninhado, sem lambda, sem inicializador: as unidades são os sete executáveis abaixo.
 *
 * <initializers> = none
 * units = [1, 1, 2, 3, 4, 4, 5]
 * ccSum = 1 + 1 + 2 + 3 + 4 + 4 + 5 = 20
 * ccMax = 5
 * ccMean = 20 / 7
 * ccP90 = units[ceil(90*7/100)] = units[ceil(6.3)] = units[7] = 5
 * unitCount = 7
 * ccSumIncludingNested = 20
 */
public final class Branches {

    /**
     * Construtor explícito, pela regra do corpus.
     *
     * CC = 1
     */
    public Branches() {
    }

    /**
     * Nenhuma decisão: a base 1. Concatenação, comparação, negação, {@code instanceof} sem padrão
     * e {@code return} somam zero.
     *
     * zero: return
     * CC = 1
     */
    public String noDecision(Object value, int a, int b) {
        String text = "a" + a + b;
        boolean same = a == b;
        boolean negated = !same;
        boolean isText = value instanceof String;
        return text + negated + isText;
    }

    /**
     * Um {@code if} com {@code else}: o {@code else} é o outro lado da mesma decisão.
     *
     * zero: else return
     * CC = 1 + if = 2
     */
    public int sign(int a) {
        if (a > 0) {
            return 1;
        } else {
            return -1;
        }
    }

    /**
     * Um {@code instanceof} com padrão é zero; o {@code &&} que o segue conta.
     *
     * CC = 1 + if + && = 3
     */
    public int lengthOf(Object value) {
        if (value instanceof String text && !text.isEmpty()) {
            return text.length();
        }
        return 0;
    }

    /**
     * Cadeia {@code else if} de três: cada {@code else if} é um {@code CtIf} aninhado no
     * {@code else} do anterior e já conta como {@code if}; o {@code else} final é zero.
     *
     * zero: else
     * CC = 1 + 3*if = 4
     */
    public String magnitude(int a) {
        if (a < 0) {
            return "negativo";
        } else if (a == 0) {
            return "zero";
        } else if (a < 10) {
            return "pequeno";
        } else {
            return "grande";
        }
    }

    /**
     * Três ternários aninhados no ramo falso um do outro.
     *
     * CC = 1 + 3*?: = 4
     */
    public String magnitudeInline(int a) {
        return a < 0 ? "negativo" : a == 0 ? "zero" : a < 10 ? "pequeno" : "grande";
    }

    /**
     * Seis operadores ansiosos que somam zero ({@code & | ^} entre booleanos, {@code &= |= ^=}
     * e {@code & |} entre inteiros) e uma condição {@code (a && b) || (c && acc)}, com dois
     * {@code &&} e um {@code ||}.
     *
     * zero: & | ^ &= |= ^=
     * CC = 1 + if + 2*&& + || = 5
     */
    public boolean shortCircuitOnly(boolean a, boolean b, boolean c, int x) {
        boolean eager = a & b | c ^ a;
        boolean acc = a;
        acc &= b;
        acc |= c;
        acc ^= eager;
        int bits = x & 0xFF | x >> 2;
        if (a && b || c && acc) {
            return bits > 0;
        }
        return false;
    }
}
