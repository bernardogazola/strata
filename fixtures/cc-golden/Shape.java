/**
 * Golden de complexidade ciclomática: interface. Os métodos abstratos não têm unidade: nem
 * cc = 1, nem cc = 0, nenhuma linha. O método {@code default}, o estático e o privado têm corpo e
 * são unidades.
 *
 * <initializers> = none
 * units = [2, 2, 3]
 * ccSum = 2 + 2 + 3 = 7
 * ccMax = 3
 * ccMean = 7 / 3
 * ccP90 = units[ceil(90*3/100)] = units[ceil(2.7)] = units[3] = 3
 * unitCount = 3
 * ccSumIncludingNested = 7
 */
public interface Shape {

    /**
     * Abstrato: nenhuma unidade.
     *
     * CC = none
     */
    double area();

    /**
     * Abstrato: nenhuma unidade.
     *
     * CC = none
     */
    String name();

    /**
     * Método {@code default}: tem corpo, é unidade.
     *
     * CC = 1 + ?: = 2
     */
    default String describe() {
        return area() > 100 ? "grande " + name() : name();
    }

    /**
     * Método estático de interface: unidade.
     *
     * CC = 1 + if + || = 3
     */
    static boolean valid(Shape shape) {
        if (shape == null || shape.area() < 0) {
            return false;
        }
        return true;
    }

    /**
     * Método privado de interface: unidade.
     *
     * CC = 1 + && = 2
     */
    private boolean tiny() {
        return area() > 0 && area() < 1;
    }
}
