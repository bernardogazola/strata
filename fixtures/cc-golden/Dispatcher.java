import java.util.List;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

/**
 * Golden de complexidade ciclomática: classe anônima e classe local. Os executáveis delas são
 * unidades próprias, de dono {@code Dispatcher} (o tipo elemento mais próximo): o método que as
 * contém não soma as decisões delas, mas o {@code ccSum} do tipo soma. Inclui as duas unidades sem
 * elemento (o inicializador de instância da anônima e o construtor da local), que contam só no
 * tipo, e um lambda dentro de um método anônimo, que pesa nesse método e não no que o envolve. O
 * construtor implícito que o Spoon dá a cada anônima não é unidade.
 *
 * <p>O {@code run()} da anônima e o {@code run()} do próprio tipo têm o mesmo dono, nome e aridade:
 * são um só elemento, {@code Dispatcher#run()}, e duas unidades. O elemento soma as duas, 2 + 3 = 5,
 * com máximo 3; as linhas dele são as da declaração do dono.
 *
 * <initializers> = none
 * units = [1, 1, 2, 2, 2, 2, 2, 3, 3, 3]
 * ccSum = 1 + 1 + 2 + 2 + 2 + 2 + 2 + 3 + 3 + 3 = 21
 * ccMax = 3
 * ccMean = 21 / 10
 * ccP90 = units[ceil(90*10/100)] = units[ceil(9.0)] = units[9] = 3
 * unitCount = 10
 * ccSumIncludingNested = 21
 */
public final class Dispatcher {

    private int pending;

    /**
     * Construtor explícito, pela regra do corpus.
     *
     * CC = 1
     */
    public Dispatcher() {
        pending = 0;
    }

    /**
     * O {@code run()} do próprio tipo: é a declaração cujas linhas o elemento {@code Dispatcher#run()}
     * carrega.
     *
     * CC = 1 + if = 2
     */
    public void run() {
        if (pending > 0) {
            pending--;
        }
    }

    /**
     * Só o ternário é deste método; o inicializador e o {@code run()} da anônima são unidades
     * próprias.
     *
     * CC = 1 + ?: = 2
     */
    public Runnable later(boolean urgent) {
        int delay = urgent ? 0 : 10;
        return new Runnable() {

            private int ticks;

            /**
             * Inicializador de instância da classe anônima: unidade sem elemento, de dono
             * {@code Dispatcher}.
             *
             * element = none
             * CC = 1 + if = 2
             */
            {
                if (delay > 5) {
                    ticks = delay;
                }
            }

            /**
             * Método da anônima: unidade própria; o elemento é {@code Dispatcher#run()}, que tem as
             * linhas do {@code run()} do dono e soma as duas unidades.
             *
             * element = method:Dispatcher#run()
             * CC = 1 + for + && = 3
             */
            @Override
            public void run() {
                for (int i = 0; i < ticks && i < 100; i++) {
                    pending++;
                }
            }
        };
    }

    /**
     * Só o {@code for} aprimorado é deste método; o construtor e o método da classe local são
     * unidades próprias.
     *
     * CC = 1 + foreach = 2
     */
    public int countLong(List<String> words) {
        class Counter {

            private int count;

            /**
             * Construtor de classe local: unidade sem elemento, de dono {@code Dispatcher}.
             *
             * element = none
             * CC = 1 + ?: = 2
             */
            Counter(int start) {
                count = start < 0 ? 0 : start;
            }

            /**
             * Método de classe local: unidade própria; o elemento é
             * {@code Dispatcher#accept(String)}.
             *
             * CC = 1 + if + || = 3
             */
            void accept(String word) {
                if (word.length() > 5 || word.contains("-")) {
                    count++;
                }
            }
        }
        Counter counter = new Counter(0);
        for (String word : words) {
            counter.accept(word);
        }
        return counter.count;
    }

    /**
     * Nada aqui: o lambda e o ternário estão dentro do método da anônima.
     *
     * CC = 1
     */
    public IntUnaryOperator mirror() {
        return new IntUnaryOperator() {

            /**
             * O {@code &&} do lambda pesa aqui, no método anônimo que o contém, e não em
             * {@code mirror()}.
             *
             * CC = 1 + && + ?: = 3
             */
            @Override
            public int applyAsInt(int x) {
                IntPredicate small = y -> y > 0 && y < 10;
                return small.test(x) ? x : -x;
            }
        };
    }
}
