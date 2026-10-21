/**
 * Golden de complexidade ciclomática: ternários constantes em valores de anotação (no método, em
 * um parâmetro e em uma variável local dentro do corpo). Um valor de anotação é expressão constante,
 * dobrada pelo compilador: não há desvio em tempo de execução, e a varredura poda toda
 * {@code CtAnnotation}. Começar a varredura pelo corpo não bastaria, porque a anotação da variável
 * local está dentro dele.
 *
 * <p>{@code Limit} é um tipo anotação membro: elemento próprio, com zero unidades, porque o elemento
 * {@code value()} dele é um {@code CtAnnotationMethod} sem corpo. {@code WIDE} tem inicializador
 * sem decisão: não há {@code <initializers>}.
 *
 * <initializers> = none
 * units = [1, 1]
 * ccSum = 1 + 1 = 2
 * ccMax = 1
 * ccMean = 2 / 2
 * ccP90 = units[ceil(90*2/100)] = units[ceil(1.8)] = units[2] = 1
 * unitCount = 2
 * ccSumIncludingNested = 2 + Limit 0 = 2
 */
public final class OpenAnnotations {

    private static final boolean WIDE = true;

    /**
     * Tipo anotação membro.
     *
     * <initializers> = none
     * units = []
     * ccSum = 0
     * ccMax = 0
     * ccMean = 0
     * ccP90 = 0
     * unitCount = 0
     * ccSumIncludingNested = 0
     */
    @interface Limit {

        /**
         * Elemento de tipo anotação: sem corpo, nenhuma unidade.
         *
         * CC = none
         */
        int value();
    }

    /**
     * Construtor explícito, pela regra do corpus.
     *
     * CC = 1
     */
    public OpenAnnotations() {
    }

    /**
     * Três ternários constantes, um em cada posição de anotação; nenhum é desvio.
     *
     * zero: return
     * CC = 1
     */
    @Limit(WIDE ? 10 : 5)
    public int annotated(@Limit(WIDE ? 1 : 0) int x) {
        @Limit(WIDE ? 2 : 3)
        int local = x;
        return local;
    }
}
