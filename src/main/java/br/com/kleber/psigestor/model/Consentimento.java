package br.com.kleber.psigestor.model;

import java.time.LocalDateTime;

public record Consentimento(Integer id, int pacienteId, TipoConsentimento tipo, boolean concedido,
                            LocalDateTime registradoEm, String observacao) { }
