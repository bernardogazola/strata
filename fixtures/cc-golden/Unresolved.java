import com.thirdparty.Mode;

/**
 * Golden de complexidade ciclomática: {@code switch} sobre um enum que o lote não resolve. O
 * {@code com.thirdparty.Mode} não está no golden, e o Spoon roda sem classpath: os rótulos chegam
 * como nomes soltos de um tipo desconhecido, e cada um é uma expressão de rótulo como a de um enum
 * resolvido, com +1 por nome nas duas sintaxes; {@code default} soma zero.
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
public final class Unresolved {

    /**
     * Construtor explícito, pela regra do corpus.
     *
     * CC = 1
     */
    public Unresolved() {
    }

    /**
     * Seta: {@code case ON, AUTO ->} é +2 e {@code case OFF ->} é +1.
     *
     * zero: default
     * CC = 1 + 3*case = 4
     */
    public int level(Mode mode) {
        return switch (mode) {
            case ON, AUTO -> 2;
            case OFF -> 0;
            default -> -1;
        };
    }

    /**
     * Dois-pontos com queda: {@code case ON:} vazio cai em {@code case AUTO:}, e são dois rótulos.
     *
     * zero: default return
     * CC = 1 + 2*case = 3
     */
    public boolean active(Mode mode) {
        switch (mode) {
            case ON:
            case AUTO:
                return true;
            default:
                return false;
        }
    }
}
