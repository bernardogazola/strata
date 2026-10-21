import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.Iterator;

/**
 * Golden de complexidade ciclomática: laços, exceções, {@code assert} e os construtos que somam
 * zero ({@code try}, {@code finally}, {@code synchronized}, {@code throw}, rótulos). É também o
 * tipo de dez unidades do {@code ccP90}: o posto mais próximo é o 9º, e os valores foram escolhidos
 * para que o 9º (7) difira do 10º (10) e da interpolação linear (7,3).
 *
 * <p>O campo {@code attempts} não tem inicializador: não há {@code <initializers>}.
 *
 * <initializers> = none
 * units = [1, 1, 2, 3, 4, 5, 5, 6, 7, 10]
 * ccSum = 1 + 1 + 2 + 3 + 4 + 5 + 5 + 6 + 7 + 10 = 44
 * ccMax = 10
 * ccMean = 44 / 10
 * ccP90 = units[ceil(90*10/100)] = units[ceil(9.0)] = units[9] = 7
 * unitCount = 10
 * ccSumIncludingNested = 44
 */
public final class ControlFlow {

    private int attempts;

    /**
     * Construtor explícito, pela regra do corpus.
     *
     * CC = 1
     */
    public ControlFlow() {
        attempts = 0;
    }

    /**
     * Um de cada laço: {@code for}, {@code for} aprimorado, {@code while} e {@code do}.
     *
     * CC = 1 + for + foreach + while + do = 5
     */
    public int everyLoop(int[] values, int n) {
        int total = 0;
        for (int i = 0; i < n; i++) {
            total += i;
        }
        for (int value : values) {
            total += value;
        }
        int k = n;
        while (k > 0) {
            k--;
        }
        do {
            total--;
        } while (total > 100);
        return total;
    }

    /**
     * Rótulo, {@code continue} e {@code break} rotulados somam zero; o {@code &&} na condição do
     * {@code for} externo conta.
     *
     * zero: label return
     * CC = 1 + 2*for + && + 2*if = 6
     */
    public int rowOf(int[][] grid, int target) {
        int found = -1;
        outer:
        for (int i = 0; i < grid.length && found < 0; i++) {
            for (int j = 0; j < grid[i].length; j++) {
                if (grid[i][j] < 0) {
                    continue outer;
                }
                if (grid[i][j] == target) {
                    found = i;
                    break outer;
                }
            }
        }
        return found;
    }

    /**
     * {@code for (;;)} e {@code while (true)}: condição constante ou ausente, o laço conta igual.
     *
     * CC = 1 + for + while + 2*if = 5
     */
    public int drain(Iterator<Integer> source) {
        int sum = 0;
        for (;;) {
            if (!source.hasNext()) {
                break;
            }
            sum += source.next();
        }
        while (true) {
            if (sum < 10) {
                return sum;
            }
            sum /= 2;
        }
    }

    /**
     * Multi-catch {@code (A | B e)} é UM ramo; a segunda cláusula é outro. {@code try},
     * {@code finally} e {@code throw} somam zero.
     *
     * zero: try finally throw
     * CC = 1 + 2*catch = 3
     */
    public int parse(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException | NullPointerException e) {
            return -1;
        } catch (RuntimeException e) {
            throw new IllegalStateException(e);
        } finally {
            attempts++;
        }
    }

    /**
     * {@code synchronized} e try-with-resources somam zero; a única cláusula {@code catch} conta.
     *
     * zero: synchronized try throw
     * CC = 1 + catch = 2
     */
    public String firstLine(Reader reader) {
        synchronized (this) {
            try (BufferedReader buffered = new BufferedReader(reader)) {
                return buffered.readLine();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /**
     * Só construtos de peso zero: bloco rotulado, {@code synchronized}, {@code try}/{@code finally},
     * {@code break} rotulado e {@code throw}.
     *
     * zero: label synchronized try finally throw
     * CC = 1
     */
    public void zeros(Object lock) {
        done:
        {
            synchronized (lock) {
                try {
                    lock.notifyAll();
                    break done;
                } finally {
                    attempts++;
                }
            }
        }
        throw new UnsupportedOperationException("fim");
    }

    /**
     * Cada {@code assert} é um desvio em tempo de execução; o {@code :} da mensagem não é um
     * ternário; o {@code &&} na condição do segundo conta.
     *
     * CC = 1 + 2*assert + && = 4
     */
    public int checkedSum(int a, int b) {
        assert a >= 0 : "a negativo";
        assert a < 100 && b < 100;
        return a + b;
    }

    /**
     * {@code do} com condição composta, {@code catch} dentro do laço, um {@code if} com
     * {@code ||} e um ternário no retorno.
     *
     * CC = 1 + do + && + catch + if + || + ?: = 7
     */
    public int retry(int maxAttempts) {
        int tries = 0;
        boolean done = false;
        do {
            tries++;
            try {
                done = parse(String.valueOf(tries)) > 1;
            } catch (IllegalStateException e) {
                if (tries >= maxAttempts || maxAttempts <= 0) {
                    return -1;
                }
            }
        } while (!done && tries < maxAttempts);
        return done ? tries : -tries;
    }

    /**
     * A maior unidade do tipo: guarda com {@code ||}, um {@code for} com uma cadeia
     * {@code if}/{@code else if}, um {@code ||} em uma atribuição, um {@code while} e um ternário.
     *
     * zero: else
     * CC = 1 + 3*if + 2*|| + for + && + while + ?: = 10
     */
    public int countTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int count = 0;
        boolean inWord = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            boolean letter = Character.isLetterOrDigit(ch) || ch == '_';
            if (letter && !inWord) {
                count++;
                inWord = true;
            } else if (!letter) {
                inWord = false;
            }
        }
        while (count > 1000) {
            count /= 10;
        }
        return count > 0 ? count : 0;
    }
}
