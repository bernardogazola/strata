/**
 * Golden de complexidade ciclomática: classe abstrata. O método abstrato não tem unidade, e o
 * nativo também não: o critério é ter corpo, e não deixar de ser abstrato. Os métodos abstratos
 * herdados de {@code Shape} não são declarados aqui e não contam em lugar nenhum.
 *
 * <initializers> = none
 * units = [2, 3]
 * ccSum = 2 + 3 = 5
 * ccMax = 3
 * ccMean = 5 / 2
 * ccP90 = units[ceil(90*2/100)] = units[ceil(1.8)] = units[2] = 3
 * unitCount = 2
 * ccSumIncludingNested = 5
 */
public abstract class Polygon implements Shape {

    private final int sides;

    /**
     * Construtor de classe abstrata: unidade.
     *
     * zero: throw
     * CC = 1 + if = 2
     */
    protected Polygon(int sides) {
        if (sides < 3) {
            throw new IllegalArgumentException("lados: " + sides);
        }
        this.sides = sides;
    }

    /**
     * Abstrato: nenhuma unidade.
     *
     * CC = none
     */
    public abstract double perimeter();

    /**
     * Nativo: sem corpo, nenhuma unidade.
     *
     * CC = none
     */
    protected static native int checksum(byte[] data);

    /**
     * Um {@code &&} na condição de um ternário.
     *
     * CC = 1 + && + ?: = 3
     */
    @Override
    public String name() {
        return sides == 4 && perimeter() > 0 ? "quadrilatero" : "poligono de " + sides + " lados";
    }
}
