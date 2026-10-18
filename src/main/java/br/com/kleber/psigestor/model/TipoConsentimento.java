package br.com.kleber.psigestor.model;

public enum TipoConsentimento {
    CONTRATO_TERAPEUTICO("Contrato terapeutico / ciencia do tratamento de dados"),
    CONTATO_WHATSAPP_EMAIL("Aceita lembretes por WhatsApp/e-mail"),
    ATENDIMENTO_ONLINE("Ciencia e aceite do atendimento online");

    private final String descricao;

    TipoConsentimento(String descricao) { this.descricao = descricao; }

    public String getDescricao() { return descricao; }

    @Override public String toString() { return descricao; }
}
