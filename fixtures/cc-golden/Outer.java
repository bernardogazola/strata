/**
 * Golden de complexidade ciclomática: tipos nomeados aninhados. {@code Inner} e {@code Deepest}
 * são elementos próprios e ficam fora do {@code ccSum} de {@code Outer}; só
 * {@code ccSumIncludingNested}, que é exibição e nunca métrica de coorte, os soma. A classe anônima
 * criada em {@code Inner.task()} é de {@code Inner}, o tipo elemento mais próximo, e não de
 * {@code Outer}.
 *
 * <initializers> = none
 * units = [1, 2]
 * ccSum = 1 + 2 = 3
 * ccMax = 2
 * ccMean = 3 / 2
 * ccP90 = units[ceil(90*2/100)] = units[ceil(1.8)] = units[2] = 2
 * unitCount = 2
 * ccSumIncludingNested = 3 + Inner 10 = 13
 */
public final class Outer {

    /**
     * Construtor explícito, pela regra do corpus.
     *
     * CC = 1
     */
    public Outer() {
    }

    /**
     * Um {@code if}.
     *
     * CC = 1 + if = 2
     */
    public int clamp(int a) {
        if (a > 0) {
            return a;
        }
        return 0;
    }

    /**
     * Tipo membro estático: elemento próprio, com o {@code run()} da anônima de {@code task()}.
     *
     * <initializers> = none
     * units = [1, 1, 2, 3]
     * ccSum = 1 + 1 + 2 + 3 = 7
     * ccMax = 3
     * ccMean = 7 / 4
     * ccP90 = units[ceil(90*4/100)] = units[ceil(3.6)] = units[4] = 3
     * unitCount = 4
     * ccSumIncludingNested = 7 + Deepest 3 = 10
     */
    public static final class Inner {

        /**
         * Construtor explícito, pela regra do corpus.
         *
         * CC = 1
         */
        public Inner() {
        }

        /**
         * Um {@code while} com {@code &&}.
         *
         * CC = 1 + while + && = 3
         */
        public int drain(int a, int b) {
            int left = a;
            int right = b;
            while (left > 0 && right > 0) {
                left--;
                right--;
            }
            return left + right;
        }

        /**
         * Nada aqui: o {@code do} está no {@code run()} da anônima.
         *
         * CC = 1
         */
        public Runnable task() {
            return new Runnable() {

                /**
                 * Unidade de dono {@code Inner}, o tipo elemento mais próximo; o elemento é
                 * {@code Outer$Inner#run()}.
                 *
                 * CC = 1 + do = 2
                 */
                @Override
                public void run() {
                    int n = 0;
                    do {
                        n++;
                    } while (n < 3);
                }
            };
        }

        /**
         * Classe interna (não estática) de um tipo membro: elemento próprio.
         *
         * <initializers> = none
         * units = [1, 2]
         * ccSum = 1 + 2 = 3
         * ccMax = 2
         * ccMean = 3 / 2
         * ccP90 = units[ceil(90*2/100)] = units[ceil(1.8)] = units[2] = 2
         * unitCount = 2
         * ccSumIncludingNested = 3
         */
        public final class Deepest {

            /**
             * Construtor explícito, pela regra do corpus.
             *
             * CC = 1
             */
            public Deepest() {
            }

            /**
             * Um ternário.
             *
             * CC = 1 + ?: = 2
             */
            public int abs(int a) {
                return a > 0 ? a : -a;
            }
        }
    }
}
