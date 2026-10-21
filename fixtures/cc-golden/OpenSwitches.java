/**
 * Golden de complexidade ciclomática: {@code case null}, {@code case null, default} e padrões de
 * record. Cada elemento de {@code getCaseExpressions()} é +1, inclusive o literal {@code null}, e
 * {@code default} nunca é elemento: zero sozinho, zero junto de {@code null}. Um padrão de record é
 * uma só expressão de rótulo, por mais que aninhe: os padrões dos componentes não são rótulos.
 *
 * <initializers> = none
 * units = [1, 3, 3, 4]
 * ccSum = 1 + 3 + 3 + 4 = 11
 * ccMax = 4
 * ccMean = 11 / 4
 * ccP90 = units[ceil(90*4/100)] = units[ceil(3.6)] = units[4] = 4
 * unitCount = 4
 * ccSumIncludingNested = 11
 */
public final class OpenSwitches {

    /**
     * Construtor explícito, pela regra do corpus.
     *
     * CC = 1
     */
    public OpenSwitches() {
    }

    /**
     * {@code case null ->} sozinho: o {@code null} é uma expressão de rótulo, +1.
     *
     * zero: default
     * CC = 1 + 2*case = 3
     */
    public int nullAlone(String key) {
        return switch (key) {
            case null -> -1;
            case "a" -> 1;
            default -> 0;
        };
    }

    /**
     * {@code case null, default ->}: o {@code null} é +1 e o {@code default} que o acompanha é zero.
     *
     * zero: default
     * CC = 1 + 2*case = 3
     */
    public int nullOrDefault(String key) {
        return switch (key) {
            case "a" -> 1;
            case null, default -> 0;
        };
    }

    /**
     * Padrão de record: cada rótulo é uma só expressão; os padrões {@code int low} e {@code int high}
     * aninhados nele não são rótulos; a guarda é +1.
     *
     * zero: default
     * CC = 1 + 2*case + when = 4
     */
    public String shape(Object value) {
        return switch (value) {
            case Interval(int low, int high) when low == high -> "ponto";
            case Interval(int low, int high) -> "intervalo " + low + ".." + high;
            default -> "outro";
        };
    }
}
