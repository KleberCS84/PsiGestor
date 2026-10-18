package br.com.kleber.psigestor.util;

import java.math.BigDecimal;
import java.util.regex.Pattern;

public final class Validadores {

    private Validadores() { }

    public static String somenteDigitos(String s) {
        return s == null ? "" : s.replaceAll("\\D", "");
    }

    /** Valida CPF pelos dois digitos verificadores (modulo 11). */
    public static boolean cpfValido(String cpf) {
        String d = somenteDigitos(cpf);
        if (d.length() != 11 || d.chars().distinct().count() == 1) return false;
        return digito(d, 9) == d.charAt(9) - '0' && digito(d, 10) == d.charAt(10) - '0';
    }

    private static int digito(String d, int qtd) {
        int soma = 0;
        for (int i = 0; i < qtd; i++) soma += (d.charAt(i) - '0') * (qtd + 1 - i);
        int resto = (soma * 10) % 11;
        return resto == 10 ? 0 : resto;
    }

    private static final Pattern CARACTERES_TELEFONE = Pattern.compile("[0-9()+\\-.\\s]+");

    /** Telefone brasileiro com DDD: 10 (fixo) ou 11 (celular) digitos, opcionalmente com +55 na frente. */
    public static boolean telefoneValido(String telefone) {
        if (vazio(telefone) || !CARACTERES_TELEFONE.matcher(telefone).matches()) return false;
        String d = somenteDigitos(telefone);
        if ((d.length() == 12 || d.length() == 13) && d.startsWith("55")) d = d.substring(2);
        return d.length() == 10 || d.length() == 11;
    }

    public static boolean vazio(String s) {
        return s == null || s.isBlank();
    }

    private static final Pattern PONTO_DECIMAL = Pattern.compile("\\d+\\.\\d{1,2}");          // 150.00, 7.5
    private static final Pattern VIRGULA_DECIMAL = Pattern.compile("\\d+(,\\d{1,2})?");        // 150, 80,50
    private static final Pattern COM_MILHAR = Pattern.compile("\\d{1,3}(\\.\\d{3})+(,\\d{1,2})?"); // 1.234,56

    /**
     * Converte valor digitado (dinheiro ou percentual) sem adivinhar formato.
     * Ponto seguido de 1-2 digitos e decimal; ponto seguido de 3 digitos so e milhar se houver
     * virgula ou outro grupo ("1.234" sozinho e ambiguo: 1234 no Brasil, 1,234 nos EUA - rejeitado).
     * Aceita no maximo 2 casas decimais; recusa sinal, expoente, NaN e Infinity. Vazio = 0.
     */
    public static BigDecimal numeroBR(String s) {
        if (vazio(s)) return BigDecimal.ZERO;
        String t = s.trim();
        if (PONTO_DECIMAL.matcher(t).matches()) return new BigDecimal(t);
        if (VIRGULA_DECIMAL.matcher(t).matches()) return new BigDecimal(t.replace(",", "."));
        if (COM_MILHAR.matcher(t).matches()) {
            if (!t.contains(",") && t.indexOf('.') == t.lastIndexOf('.'))
                throw new RegraNegocioException("Valor ambiguo: " + s + ". Use virgula para decimais (ex.: 1.234,00).");
            return new BigDecimal(t.replace(".", "").replace(",", "."));
        }
        throw new RegraNegocioException("Valor invalido: " + s);
    }

    /** Valor em reais digitado -> centavos. numeroBR ja limita a 2 casas, entao a conversao e exata. */
    public static long centavos(String s) {
        try {
            return numeroBR(s).movePointRight(2).longValueExact();
        } catch (ArithmeticException e) {
            throw new RegraNegocioException("Valor muito grande: " + s);
        }
    }
}
