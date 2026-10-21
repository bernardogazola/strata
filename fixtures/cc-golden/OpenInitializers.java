/**
 * Golden de complexidade ciclomática: o que entra em {@code <initializers>}. Argumentos de
 * constante de enum, constante de interface e campo de classe anônima, cada um com um ternário,
 * mais um campo do próprio tipo com ternário. O {@code <initializers>} é por declaração de tipo: o
 * da classe anônima de {@code holder} é dela (unidade própria, sem elemento, de dono
 * {@code OpenInitializers}) e nunca se funde no do tipo nomeado, assim como os métodos e os
 * blocos dela são unidades próprias. Os argumentos de uma constante de enum contam no
 * {@code <initializers>} do enum, e a constante de interface no da interface.
 *
 * <initializers> CC = 1 + ?: = 2
 * units = [1, 1, 1, 2, 2]
 * ccSum = 1 + 1 + 1 + 2 + 2 = 7
 * ccMax = 2
 * ccMean = 7 / 5
 * ccP90 = units[ceil(90*5/100)] = units[ceil(4.5)] = units[5] = 2
 * unitCount = 5
 * ccSumIncludingNested = 7 + Mode 3 + Defaults 2 = 12
 */
public final class OpenInitializers {

    private final int base = Integer.getInteger("golden.base", 0) > 0 ? 1 : 0;

    /**
     * Construtor explícito, pela regra do corpus.
     *
     * CC = 1
     */
    public OpenInitializers() {
    }

    /**
     * O ternário do campo da anônima não conta aqui, porque a anônima é podada; o {@code toString()}
     * dela não tem decisão.
     *
     * CC = 1
     */
    public Object holder(boolean flag) {
        return new Object() {

            /**
             * O {@code <initializers>} da classe anônima, escrito no seu único campo: unidade
             * própria, sem elemento, de dono {@code OpenInitializers}.
             *
             * <initializers> CC = 1 + ?: = 2
             */
            private final int kind = flag ? 1 : 0;

            /**
             * Unidade própria da anônima, de dono {@code OpenInitializers}.
             *
             * CC = 1
             */
            @Override
            public String toString() {
                return "tipo " + kind + base;
            }
        };
    }

    /**
     * Enum membro com argumento de constante que decide: a constante é um campo do enum, e o
     * argumento dela conta no {@code <initializers>} dele. Construtor explícito.
     *
     * <initializers> CC = 1 + ?: = 2
     * units = [1, 2]
     * ccSum = 1 + 2 = 3
     * ccMax = 2
     * ccMean = 3 / 2
     * ccP90 = units[ceil(90*2/100)] = units[ceil(1.8)] = units[2] = 2
     * unitCount = 2
     * ccSumIncludingNested = 3
     */
    enum Mode {
        FAST(Runtime.version().feature() > 20 ? 2 : 1),
        SLOW(1);

        private final int factor;

        /**
         * Construtor explícito de enum.
         *
         * CC = 1
         */
        Mode(int factor) {
            this.factor = factor;
        }
    }

    /**
     * Interface membro cuja única unidade possível é o {@code <initializers>} da constante.
     *
     * <initializers> CC = 1 + ?: = 2
     * units = [2]
     * ccSum = 2
     * ccMax = 2
     * ccMean = 2 / 1
     * ccP90 = units[ceil(90*1/100)] = units[ceil(0.9)] = units[1] = 2
     * unitCount = 1
     * ccSumIncludingNested = 2
     */
    interface Defaults {

        int SIZE = Boolean.getBoolean("golden.big") ? 64 : 8;
    }
}
