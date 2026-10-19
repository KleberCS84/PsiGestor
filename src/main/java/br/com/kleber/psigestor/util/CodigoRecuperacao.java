package br.com.kleber.psigestor.util;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Codigo de recuperacao do psicologo: 128 bits aleatorios em base32 Crockford (sem I, L, O, U),
 * 26 caracteres + 2 de verificador, exibido em 7 grupos de 4 (XXXX-XXXX-...-XXXX).
 * O verificador pega erro de digitacao (troca de um caractere ou de dois vizinhos) antes do PBKDF2.
 * O codigo so existe em memoria para ser mostrado uma vez; nunca e gravado nem registrado em log.
 */
public final class CodigoRecuperacao {

    static final String ALFABETO = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    static final int TAM_DADOS = 26;   // 26 x 5 bits = 130 >= 128
    static final int TAM_TOTAL = TAM_DADOS + 2;
    private static final int BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Mensagem fixa: nunca ecoa o que foi digitado. */
    public static final String DIGITADO_ERRADO = "Codigo de recuperacao digitado incorretamente. Confira os caracteres.";

    private CodigoRecuperacao() { }

    /** Codigo novo, formatado para exibicao. */
    public static String gerar() {
        byte[] b = new byte[BYTES];
        RANDOM.nextBytes(b);
        BigInteger n = new BigInteger(1, b);
        Arrays.fill(b, (byte) 0);
        char[] c = new char[TAM_TOTAL];
        for (int i = TAM_DADOS - 1; i >= 0; i--) {
            c[i] = ALFABETO.charAt(n.intValue() & 31);
            n = n.shiftRight(5);
        }
        int v = verificador(c);
        c[TAM_DADOS] = ALFABETO.charAt(v >> 5);
        c[TAM_DADOS + 1] = ALFABETO.charAt(v & 31);
        String formatado = formatar(c);
        Arrays.fill(c, '\0');
        return formatado;
    }

    /**
     * Forma canonica (28 caracteres, sem hifens) para o PBKDF2. Aceita minusculas, espacos e hifens,
     * e le O como 0 e I/L como 1. Tamanho ou verificador errado: RegraNegocioException com mensagem fixa.
     */
    public static char[] normalizar(String digitado) {
        if (digitado == null) throw new RegraNegocioException(DIGITADO_ERRADO);
        char[] c = new char[TAM_TOTAL];
        int n = 0;
        for (int i = 0; i < digitado.length(); i++) {
            char ch = Character.toUpperCase(digitado.charAt(i));
            if (ch == '-' || Character.isWhitespace(ch)) continue;
            if (ch == 'O') ch = '0';
            else if (ch == 'I' || ch == 'L') ch = '1';
            if (ALFABETO.indexOf(ch) < 0 || n == TAM_TOTAL) {
                Arrays.fill(c, '\0');
                throw new RegraNegocioException(DIGITADO_ERRADO);
            }
            c[n++] = ch;
        }
        int v = verificador(c);
        if (n != TAM_TOTAL || c[TAM_DADOS] != ALFABETO.charAt(v >> 5) || c[TAM_DADOS + 1] != ALFABETO.charAt(v & 31)) {
            Arrays.fill(c, '\0');
            throw new RegraNegocioException(DIGITADO_ERRADO);
        }
        return c;
    }

    /** Os 4 ultimos caracteres, para a confirmacao de que o codigo foi anotado. */
    public static String ultimos4(String codigo) {
        String s = codigo.replace("-", "");
        return s.substring(s.length() - 4);
    }

    /** Soma ponderada pela posicao, mod 1024 (2 caracteres base32). */
    private static int verificador(char[] c) {
        int soma = 0;
        for (int i = 0; i < TAM_DADOS; i++) soma += (i + 1) * Math.max(0, ALFABETO.indexOf(c[i]));
        return soma & 1023;
    }

    private static String formatar(char[] c) {
        StringBuilder sb = new StringBuilder(TAM_TOTAL + 6);
        for (int i = 0; i < TAM_TOTAL; i++) {
            if (i > 0 && i % 4 == 0) sb.append('-');
            sb.append(c[i]);
        }
        return sb.toString();
    }
}
