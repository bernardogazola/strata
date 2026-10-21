/**
 * Golden de complexidade ciclomática: record com construtor canônico compacto, que é unidade. Os
 * dois acessores são declarados de propósito, e são unidades; os implícitos ficam com
 * {@code OpenImplicit}.
 *
 * <initializers> = none
 * units = [1, 1, 2, 2, 3]
 * ccSum = 1 + 1 + 2 + 2 + 3 = 9
 * ccMax = 3
 * ccMean = 9 / 5
 * ccP90 = units[ceil(90*5/100)] = units[ceil(4.5)] = units[5] = 3
 * unitCount = 5
 * ccSumIncludingNested = 9
 */
public record Interval(int low, int high) {

    /**
     * Construtor canônico compacto.
     *
     * zero: throw
     * CC = 1 + if + || = 3
     */
    public Interval {
        if (low > high || low < 0) {
            throw new IllegalArgumentException("intervalo invalido");
        }
    }

    /**
     * Acessor explícito.
     *
     * CC = 1
     */
    @Override
    public int low() {
        return low;
    }

    /**
     * Acessor explícito.
     *
     * CC = 1
     */
    @Override
    public int high() {
        return high;
    }

    /**
     * Um {@code &&}.
     *
     * CC = 1 + && = 2
     */
    public boolean overlaps(Interval other) {
        return low <= other.high && other.low <= high;
    }

    /**
     * Fábrica estática com ternário.
     *
     * CC = 1 + ?: = 2
     */
    public static Interval of(int a, int b) {
        return a <= b ? new Interval(a, b) : new Interval(b, a);
    }
}
