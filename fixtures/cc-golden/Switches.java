import java.time.DayOfWeek;
import java.util.function.IntUnaryOperator;

/**
 * Golden de complexidade ciclomática para {@code switch}: rótulo antigo com queda, seta com várias
 * expressões ({@code case A, B, C ->} é +3), {@code default} que soma zero, {@code switch} como
 * expressão com {@code yield} (cada rótulo conta como no comando), padrão de tipo com guarda
 * {@code when} (+1 a mais, e o {@code &&} da guarda também) e um {@code switch} dentro de um lambda,
 * que pesa no método que o contém.
 *
 * <p>Em {@code classic}, o rótulo vazio que cai no seguinte conta como qualquer outro:
 * {@code case 1: case 2:} vale +2, assim como {@code case 1, 2:}; as duas sintaxes concordam.
 *
 * <initializers> = none
 * units = [1, 4, 4, 5, 6, 6, 6]
 * ccSum = 1 + 4 + 4 + 5 + 6 + 6 + 6 = 32
 * ccMax = 6
 * ccMean = 32 / 7
 * ccP90 = units[ceil(90*7/100)] = units[ceil(6.3)] = units[7] = 6
 * unitCount = 7
 * ccSumIncludingNested = 32
 */
public final class Switches {

    /**
     * Construtor explícito, pela regra do corpus.
     *
     * CC = 1
     */
    public Switches() {
    }

    /**
     * {@code switch} antigo: {@code case 1:} e {@code case 2:} são dois rótulos, cada um com uma
     * expressão, mesmo com o primeiro vazio caindo no segundo; {@code default} soma zero.
     *
     * zero: default
     * CC = 1 + 3*case = 4
     */
    public String classic(int code) {
        String label;
        switch (code) {
            case 1:
            case 2:
                label = "baixo";
                break;
            case 3:
                label = "medio";
                break;
            default:
                label = "outro";
        }
        return label;
    }

    /**
     * Seta com várias expressões: {@code case MONDAY, TUESDAY, WEDNESDAY ->} é +3 e
     * {@code case THURSDAY, FRIDAY ->} é +2; {@code default} soma zero.
     *
     * zero: default
     * CC = 1 + 5*case = 6
     */
    public int hours(DayOfWeek day) {
        int hours;
        switch (day) {
            case MONDAY, TUESDAY, WEDNESDAY -> hours = 8;
            case THURSDAY, FRIDAY -> hours = 6;
            default -> hours = 0;
        }
        return hours;
    }

    /**
     * {@code switch} como expressão, seta e {@code yield}: quatro expressões de rótulo
     * ({@code "a", "b"}, {@code "c"}, {@code "d"}), um {@code if} dentro de um ramo; {@code yield}
     * e {@code default} somam zero.
     *
     * zero: yield default
     * CC = 1 + 4*case + if = 6
     */
    public int weight(String key) {
        return switch (key) {
            case "a", "b" -> 1;
            case "c" -> {
                if (key.isBlank()) {
                    yield 0;
                }
                yield 3;
            }
            case "d" -> {
                yield 4;
            }
            default -> {
                yield -1;
            }
        };
    }

    /**
     * {@code switch} como expressão com rótulos de dois-pontos e {@code yield}; um ternário dentro
     * do {@code yield}.
     *
     * zero: yield default
     * CC = 1 + 2*case + ?: = 4
     */
    public int legacyWeight(int k) {
        int weight = switch (k) {
            case 1:
                yield 10;
            case 2:
                yield k > 5 ? 20 : 21;
            default:
                yield 0;
        };
        return weight;
    }

    /**
     * Padrões de tipo: três rótulos de uma expressão cada; a guarda {@code when} é +1 a mais e o
     * {@code &&} dentro dela é outro; {@code default} soma zero.
     *
     * zero: default
     * CC = 1 + 3*case + when + && = 6
     */
    public String describe(Object value) {
        return switch (value) {
            case Integer i when i > 0 && i < 10 -> "inteiro pequeno";
            case Integer i -> "inteiro";
            case String s -> "texto " + s;
            default -> "outro";
        };
    }

    /**
     * Um {@code switch} como expressão no corpo de um lambda: as decisões do lambda são deste
     * método. {@code case 1, 2 ->} é +2; o ternário é +1.
     *
     * zero: default
     * CC = 1 + 3*case + ?: = 5
     */
    public IntUnaryOperator bucket() {
        return n -> switch (n % 3) {
            case 0 -> n;
            case 1, 2 -> n < 0 ? -n : n;
            default -> 0;
        };
    }
}
