import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Golden de complexidade ciclomática: o lambda é código em linha, não declaração. As decisões de
 * um lambda (aninhado, de bloco ou de expressão) pesam no executável que o contém, e um lambda
 * em um inicializador de campo pesa no {@code <initializers>}. Uma classe anônima dentro de um
 * lambda é podada: o {@code run()} dela é unidade própria, de dono {@code Lambdas}.
 *
 * <p>{@code <initializers>}: o ternário de {@code LIMIT} (estático) e o {@code &&} do lambda de
 * {@code nonBlank} (de instância), em uma unidade só para o tipo.
 *
 * <initializers> CC = 1 + ?: + && = 3
 * units = [1, 1, 2, 2, 3, 4, 4]
 * ccSum = 1 + 1 + 2 + 2 + 3 + 4 + 4 = 17
 * ccMax = 4
 * ccMean = 17 / 7
 * ccP90 = units[ceil(90*7/100)] = units[ceil(6.3)] = units[7] = 4
 * unitCount = 7
 * ccSumIncludingNested = 17
 */
public final class Lambdas {

    private static final int LIMIT = Integer.getInteger("golden.limit", 3) > 0 ? 3 : 1;

    private final Predicate<String> nonBlank = s -> s != null && !s.isBlank();

    /**
     * Construtor explícito, pela regra do corpus.
     *
     * CC = 1
     */
    public Lambdas() {
    }

    /**
     * Dois lambdas na mesma cadeia: o de expressão traz um {@code &&}; o de bloco, um {@code if} e
     * um ternário. Tudo pesa aqui.
     *
     * CC = 1 + && + if + ?: = 4
     */
    public List<String> shorten(List<String> words) {
        return words.stream()
                .filter(w -> w != null && !w.isEmpty())
                .map(w -> {
                    if (w.length() > LIMIT) {
                        return w.substring(0, LIMIT);
                    }
                    return w.isBlank() ? "_" : w;
                })
                .toList();
    }

    /**
     * Lambda dentro de lambda: {@code a > b ? a : ((a < 0 || b < 0) ? 0 : b)}, com dois ternários e
     * um {@code ||}, todos deste método.
     *
     * CC = 1 + 2*?: + || = 4
     */
    public Function<Integer, Function<Integer, Integer>> curried() {
        return a -> b -> a > b ? a : a < 0 || b < 0 ? 0 : b;
    }

    /**
     * Referências de método e um lambda vazio: nada a contar.
     *
     * zero: return
     * CC = 1
     */
    public Supplier<String> references(String text) {
        Function<String, Integer> length = String::length;
        Runnable nothing = () -> { };
        nothing.run();
        length.apply(text);
        return text::trim;
    }

    /**
     * O {@code if} do lambda pesa aqui; o {@code while} do {@code run()} da classe anônima criada
     * dentro do lambda não pesa, porque ela é podada.
     *
     * CC = 1 + if = 2
     */
    public Supplier<Runnable> deferred(boolean quick) {
        return () -> {
            if (quick) {
                return () -> { };
            }
            return new Runnable() {

                /**
                 * Unidade própria da classe anônima, de dono {@code Lambdas}.
                 *
                 * CC = 1 + while = 2
                 */
                @Override
                public void run() {
                    int n = 3;
                    while (n > 0) {
                        n--;
                    }
                }
            };
        };
    }
}
