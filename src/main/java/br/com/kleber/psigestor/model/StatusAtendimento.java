package br.com.kleber.psigestor.model;

public enum StatusAtendimento {
    AGENDADO, CONFIRMADO, REALIZADO, FALTOU, CANCELADO;

    /** Texto de tela; o banco guarda name(). */
    public String rotulo() {
        return switch (this) {
            case AGENDADO -> "Agendado";
            case CONFIRMADO -> "Confirmado";
            case REALIZADO -> "Realizado";
            case FALTOU -> "Faltou";
            case CANCELADO -> "Cancelado";
        };
    }

    /** Status que ocupam o horario na agenda (usado na checagem de conflito). */
    public boolean ocupaHorario() {
        return this != CANCELADO;
    }

    /**
     * Transicoes permitidas. REALIZADO/FALTOU so trocam entre si (corrigir clique errado);
     * CANCELADO pode ser reativado (o service revalida conflito de horario).
     */
    public boolean podeIrPara(StatusAtendimento destino) {
        if (destino == null || destino == this) return false;
        return switch (this) {
            case AGENDADO -> true;
            case CONFIRMADO -> destino == REALIZADO || destino == FALTOU || destino == CANCELADO;
            case CANCELADO -> destino == AGENDADO || destino == CONFIRMADO;
            case REALIZADO -> destino == FALTOU;
            case FALTOU -> destino == REALIZADO;
        };
    }
}
