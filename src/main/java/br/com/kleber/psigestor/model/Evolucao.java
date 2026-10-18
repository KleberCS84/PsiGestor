package br.com.kleber.psigestor.model;

import java.time.LocalDateTime;

/** Registro de prontuario ja decifrado (so existe em memoria, nunca em disco). */
public record Evolucao(int id, int pacienteId, Integer atendimentoId, int profissionalId,
                       LocalDateTime registradoEm, String texto, Integer retificaId) {

    public boolean isRetificacao() { return retificaId != null; }

    /** O toString padrao do record imprimiria o texto do prontuario se alguem logasse o objeto. */
    @Override
    public String toString() {
        return "Evolucao[id=" + id + ", pacienteId=" + pacienteId + ", atendimentoId=" + atendimentoId
                + ", profissionalId=" + profissionalId + ", registradoEm=" + registradoEm
                + ", retificaId=" + retificaId + ", texto=<oculto>]";
    }
}
