package br.com.kleber.psigestor.util;

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

    public static boolean vazio(String s) {
        return s == null || s.isBlank();
    }
}
