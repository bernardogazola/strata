import java.util.HashMap;
import java.util.Map;

/**
 * Golden de complexidade ciclomática: os executáveis sem nome. Dois blocos estáticos são duas
 * unidades e um só elemento {@code <clinit>()}, que soma as duas, 3 + 2 = 5, com máximo 3,
 * e tem as linhas do primeiro na ordem do arquivo. O inicializador de instância é unidade sem
 * elemento, e conta só no tipo. Os inicializadores de campo com decisão, um estático e um de
 * instância, vão para uma só unidade sintética {@code <initializers>}, também sem elemento; os dois
 * sem decisão ({@code DEFAULTS}, {@code label}) somam zero nela. O {@code this(...)} com ternário
 * pesa no construtor que o chama.
 *
 * <initializers> CC = 1 + || + ?: = 3
 * units = [2, 2, 2, 2, 2, 3, 3]
 * ccSum = 2 + 2 + 2 + 2 + 2 + 3 + 3 = 16
 * ccMax = 3
 * ccMean = 16 / 7
 * ccP90 = units[ceil(90*7/100)] = units[ceil(6.3)] = units[7] = 3
 * unitCount = 7
 * ccSumIncludingNested = 16
 */
public final class Registry {

    private static final Map<String, Integer> DEFAULTS = new HashMap<>();

    private static final boolean VERBOSE =
            Boolean.getBoolean("golden.verbose") || System.getenv("GOLDEN_VERBOSE") != null;

    private final int capacity;

    private final int threshold = Runtime.getRuntime().availableProcessors() > 4 ? 64 : 16;

    private final String label = "registro";

    private int loaded;

    /**
     * Primeiro bloco estático.
     *
     * CC = 1 + foreach + if = 3
     */
    static {
        for (String key : new String[] {"a", "bb"}) {
            if (!key.isEmpty()) {
                DEFAULTS.put(key, key.length());
            }
        }
    }

    /**
     * Inicializador de instância: unidade sem elemento.
     *
     * element = none
     * CC = 1 + while = 2
     */
    {
        int n = 0;
        while (n < 3) {
            n++;
        }
        loaded = n;
    }

    /**
     * O construtor canônico do tipo.
     *
     * CC = 1 + ?: = 2
     */
    public Registry(int capacity) {
        this.capacity = capacity < 0 ? 0 : capacity;
    }

    /**
     * O ternário no argumento de {@code this(...)} é deste construtor.
     *
     * CC = 1 + ?: = 2
     */
    public Registry() {
        this(VERBOSE ? 8 : 4);
    }

    /**
     * Segundo bloco estático: unidade própria, mesmo elemento {@code <clinit>()} do primeiro.
     *
     * zero: try
     * element = method:Registry#<clinit>()
     * CC = 1 + catch = 2
     */
    static {
        try {
            DEFAULTS.put("c", Integer.parseInt(System.getProperty("golden.c", "3")));
        } catch (NumberFormatException e) {
            DEFAULTS.put("c", 3);
        }
    }

    /**
     * Um {@code &&} no retorno.
     *
     * zero: return
     * CC = 1 + && = 2
     */
    public boolean full() {
        return loaded >= capacity && capacity > threshold - label.length();
    }
}
