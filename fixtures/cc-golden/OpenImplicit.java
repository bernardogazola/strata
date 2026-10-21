/**
 * Golden de complexidade ciclomática: membros implícitos. É o único arquivo do golden que quebra a
 * regra do corpus de que todo tipo declara construtor explícito, e quebra de propósito. Membro
 * implícito não é unidade, pelo mesmo motivo que método abstrato não é (ninguém o escreveu, não há
 * código a ler), embora o Spoon 11.5.0 dê corpo a todos eles: o filtro é {@code isImplicit()}, e
 * não a presença de corpo. Os elementos de método deles existem, sem linhas e sem valor de CC.
 *
 * <initializers> = none
 * units = [1]
 * ccSum = 1
 * ccMax = 1
 * ccMean = 1 / 1
 * ccP90 = units[ceil(90*1/100)] = units[ceil(0.9)] = units[1] = 1
 * unitCount = 1
 * ccSumIncludingNested = 1 + Bare 2 + Pair 0 + Flag 0 = 3
 */
public final class OpenImplicit {

    /**
     * Construtor explícito: este tipo não é o caso.
     *
     * CC = 1
     */
    public OpenImplicit() {
    }

    /**
     * Classe sem construtor declarado: o construtor padrão é implícito.
     *
     * <initializers> = none
     * units = [2]
     * ccSum = 2
     * ccMax = 2
     * ccMean = 2 / 1
     * ccP90 = units[ceil(90*1/100)] = units[ceil(0.9)] = units[1] = 2
     * unitCount = 1
     * ccSumIncludingNested = 2
     */
    static final class Bare {

        /**
         * Um {@code if}.
         *
         * CC = 1 + if = 2
         */
        int one(int a) {
            if (a > 0) {
                return 1;
            }
            return 0;
        }
    }

    /**
     * Record sem membro declarado: os dois acessores implícitos não são unidades. O Spoon 11.5.0
     * não modela o construtor canônico implícito deste record.
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
    record Pair(int left, int right) {
    }

    /**
     * Enum sem construtor declarado: o construtor implícito não é unidade, e o Spoon 11.5.0 não
     * acrescenta {@code values()} nem {@code valueOf(String)}.
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
    enum Flag {
        ON,
        OFF
    }
}
