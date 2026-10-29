package br.com.kleber.psigestor.util;

/** A sessao venceu por inatividade: as telas mostram esta mensagem em vez do erro generico (B-12). */
public class SessaoEncerradaException extends RegraNegocioException {
    public static final String MENSAGEM = "Sessão encerrada por inatividade. Faça login novamente.";

    public SessaoEncerradaException() { super(MENSAGEM); }
}
