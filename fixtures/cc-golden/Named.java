/**
 * Golden de complexidade ciclomática: um tipo com zero unidades. Só métodos abstratos e nenhuma
 * constante: os agregados são os do conjunto vazio, todos 0, o que é uma medida e não a falta de
 * medida. Os dois elementos de método continuam existindo, sem valor de CC.
 *
 * <initializers> = none
 * units = []
 * ccSum = 0
 * ccMax = 0
 * ccMean = 0
 * ccP90 = 0
 * unitCount = 0
 * ccSumIncludingNested = 0
 */
public interface Named {

    /**
     * Abstrato: nenhuma unidade.
     *
     * CC = none
     */
    String label();

    /**
     * Abstrato: nenhuma unidade.
     *
     * CC = none
     */
    int priority();
}
