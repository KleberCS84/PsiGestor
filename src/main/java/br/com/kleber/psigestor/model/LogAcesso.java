package br.com.kleber.psigestor.model;

public record LogAcesso(int id, String usuario, String acao, String entidade, Integer entidadeId,
                        String dataHora, String detalhe) { }
