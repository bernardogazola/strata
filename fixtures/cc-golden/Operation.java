/**
 * Golden de complexidade ciclomática: enum com corpo de constante. O método abstrato do enum não
 * tem unidade; cada corpo de constante é uma classe anônima, e o {@code apply} de cada um é
 * unidade própria, de dono {@code Operation}. Os quatro {@code apply} são um só elemento,
 * {@code Operation#apply(int,int)}, cujas linhas são as da declaração abstrata e que soma as
 * unidades dos três corpos: 2 + 4 + 1 = 7, com máximo 4. A declaração que ele carrega não
 * tem unidade, e mesmo assim o elemento tem valor. O construtor é explícito; os argumentos das
 * constantes não têm decisão, e o corpo de cada uma é podado do {@code <initializers>}.
 *
 * <p>O Spoon 11.5.0 dá um construtor implícito a cada corpo de constante, que não é unidade, e não
 * acrescenta {@code values()} nem {@code valueOf(String)}.
 *
 * <initializers> = none
 * units = [1, 1, 2, 3, 4]
 * ccSum = 1 + 1 + 2 + 3 + 4 = 11
 * ccMax = 4
 * ccMean = 11 / 5
 * ccP90 = units[ceil(90*5/100)] = units[ceil(4.5)] = units[5] = 4
 * unitCount = 5
 * ccSumIncludingNested = 11
 */
public enum Operation {

    PLUS("+") {

        /**
         * Corpo de constante: unidade própria.
         *
         * element = method:Operation#apply(int,int)
         * CC = 1 + ?: = 2
         */
        @Override
        public int apply(int a, int b) {
            return a > Integer.MAX_VALUE - b ? Integer.MAX_VALUE : a + b;
        }
    },

    DIVIDE("/") {

        /**
         * {@code b == 0 || (a == MIN && b == -1)}: um {@code ||} e um {@code &&}.
         *
         * zero: throw
         * element = method:Operation#apply(int,int)
         * CC = 1 + if + || + && = 4
         */
        @Override
        public int apply(int a, int b) {
            if (b == 0 || a == Integer.MIN_VALUE && b == -1) {
                throw new ArithmeticException("divisao invalida");
            }
            return a / b;
        }
    },

    IDENTITY("=") {

        /**
         * Corpo de constante sem decisão.
         *
         * element = method:Operation#apply(int,int)
         * CC = 1
         */
        @Override
        public int apply(int a, int b) {
            return a;
        }
    };

    private final String symbol;

    /**
     * Construtor explícito de enum, pela regra do corpus.
     *
     * CC = 1
     */
    Operation(String symbol) {
        this.symbol = symbol;
    }

    /**
     * Abstrato: nenhuma unidade, embora seja a declaração cujas linhas o elemento que os três
     * corpos compartilham carrega.
     *
     * CC = none
     */
    public abstract int apply(int a, int b);

    /**
     * Busca linear.
     *
     * CC = 1 + foreach + if = 3
     */
    public static Operation bySymbol(String symbol) {
        for (Operation operation : values()) {
            if (operation.symbol.equals(symbol)) {
                return operation;
            }
        }
        return IDENTITY;
    }
}
