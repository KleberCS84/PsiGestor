package br.com.kleber.psigestor.util;

/** Erro de regra de negocio: a mensagem e mostrada ao usuario na tela. */
public class RegraNegocioException extends RuntimeException {
    public RegraNegocioException(String mensagem) { super(mensagem); }
}
