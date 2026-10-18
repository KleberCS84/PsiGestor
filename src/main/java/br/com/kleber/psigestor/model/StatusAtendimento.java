package br.com.kleber.psigestor.model;

public enum StatusAtendimento {
    AGENDADO, CONFIRMADO, REALIZADO, FALTOU, CANCELADO;

    /** Status que ocupam o horario na agenda (usado na checagem de conflito). */
    public boolean ocupaHorario() {
        return this != CANCELADO;
    }
}
