/**
 * Golden de complexidade ciclomática: uma classe nomeada dentro de uma classe anônima. Ela tem nome,
 * mas não é elemento: um tipo que a envolve é anônimo, e ela não tem nome canônico (JLS §6.7).
 * Por isso o dono das unidades dela é o tipo elemento mais próximo, {@code AnonymousHost}, e não o
 * tipo nomeado mais próximo, que é ela mesma. O método dela vira o elemento
 * {@code AnonymousHost#limit()}, como o de uma anônima; o construtor explícito e o
 * {@code <initializers>} dela são unidades sem elemento, que contam só aqui.
 *
 * <initializers> = none
 * units = [1, 1, 2, 2, 2, 3]
 * ccSum = 1 + 1 + 2 + 2 + 2 + 3 = 11
 * ccMax = 3
 * ccMean = 11 / 6
 * ccP90 = units[ceil(90*6/100)] = units[ceil(5.4)] = units[6] = 3
 * unitCount = 6
 * ccSumIncludingNested = 11
 */
public final class AnonymousHost {

    /**
     * Construtor explícito, pela regra do corpus.
     *
     * CC = 1
     */
    public AnonymousHost() {
    }

    /**
     * Nada aqui: a anônima e tudo o que ela declara são podados.
     *
     * CC = 1
     */
    public Runnable task(int size) {
        return new Runnable() {

            private int seen;

            /**
             * Método da anônima: unidade própria; o elemento é {@code AnonymousHost#run()}.
             *
             * CC = 1 + if = 2
             */
            @Override
            public void run() {
                Window window = new Window(size);
                if (window.limit() > 0) {
                    seen++;
                }
            }

            /**
             * Classe membro da anônima: nomeada, mas não é elemento. Não tem agregados próprios; as
             * unidades dela são de {@code AnonymousHost}.
             */
            final class Window {

                /**
                 * O {@code <initializers>} de {@code Window}, escrito no seu campo com decisão:
                 * unidade sem elemento, de dono {@code AnonymousHost}; {@code width} não tem
                 * inicializador.
                 *
                 * <initializers> CC = 1 + ?: = 2
                 */
                private final int floor = size > 100 ? 10 : 1;

                private final int width;

                /**
                 * Construtor explícito de classe sem elemento: unidade sem elemento, de dono
                 * {@code AnonymousHost}.
                 *
                 * element = none
                 * CC = 1 + ?: = 2
                 */
                Window(int width) {
                    this.width = width < floor ? floor : width;
                }

                /**
                 * Método de classe sem elemento: o elemento é {@code AnonymousHost#limit()}, de
                 * dono {@code AnonymousHost}.
                 *
                 * CC = 1 + && + ?: = 3
                 */
                int limit() {
                    return width > 0 && width < 1000 ? width : 0;
                }
            }
        };
    }
}
