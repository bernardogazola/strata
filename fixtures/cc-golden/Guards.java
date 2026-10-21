/**
 * Golden de complexidade ciclomática: a guarda {@code when} que o Spoon 11.5.0 desloca. Uma guarda
 * cuja raiz é uma invocação, um unário, uma atribuição ou um {@code new} vai parar entre os
 * comandos do caso, com {@code getGuard()} nulo ({@code ParentExiter.visitCtCase}). Na forma seta o
 * reparo é exato: se o caso tem um rótulo de padrão e exatamente dois comandos, e o primeiro não é
 * implícito, ele é a guarda e conta +1 como qualquer outra. Na forma dois-pontos a guarda deslocada
 * não se distingue do primeiro comando do corpo e não conta. É um desvio conhecido da regra, sem
 * ocorrências no corpus. A linha {@code CC} de {@code colonInvocation} é o valor da
 * regra, não o da tabela.
 *
 * <initializers> = none
 * units = [1, 3, 4]
 * ccSum = 1 + 3 + 4 = 8
 * ccMax = 4
 * ccMean = 8 / 3
 * ccP90 = units[ceil(90*3/100)] = units[ceil(2.7)] = units[3] = 4
 * unitCount = 3
 * ccSumIncludingNested = 8
 */
public final class Guards {

    /**
     * Construtor explícito, pela regra do corpus.
     *
     * CC = 1
     */
    public Guards() {
    }

    /**
     * Forma seta, guarda que é uma invocação: o Spoon a põe entre os comandos do primeiro caso, ao
     * lado do {@code yield} implícito, e o reparo a devolve. Dois rótulos de padrão e a guarda;
     * {@code default} soma zero.
     *
     * zero: default
     * CC = 1 + 2*case + when = 4
     */
    public String arrowInvocation(Object value) {
        return switch (value) {
            case String text when text.isEmpty() -> "vazio";
            case String text -> "texto " + text;
            default -> "outro";
        };
    }

    /**
     * Forma dois-pontos, a mesma guarda: pela tabela seriam dois rótulos e a guarda, 4; a guarda
     * deslocada não conta, e o valor da regra são só os dois rótulos de padrão.
     *
     * zero: default return
     * CC = 1 + 2*case = 3
     */
    public int colonInvocation(Object value) {
        switch (value) {
            case String text when text.isEmpty():
                return 0;
            case String text:
                return text.length();
            default:
                return -1;
        }
    }
}
