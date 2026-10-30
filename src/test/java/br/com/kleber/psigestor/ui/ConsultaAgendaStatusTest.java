package br.com.kleber.psigestor.ui;

import br.com.kleber.psigestor.model.StatusAtendimento;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static br.com.kleber.psigestor.model.StatusAtendimento.*;
import static org.junit.jupiter.api.Assertions.*;

/** Regra das caixas de status da tela Consultar agenda (sem JavaFX: so a funcao pura). */
class ConsultaAgendaStatusTest {

    @Test
    void marcarEDesmarcarStatus() {
        EnumSet<StatusAtendimento> padrao = EnumSet.of(AGENDADO);
        assertEquals(EnumSet.of(AGENDADO, REALIZADO), ConsultaAgendaView.marcar(padrao, REALIZADO, true));
        assertEquals(EnumSet.of(REALIZADO),
                ConsultaAgendaView.marcar(EnumSet.of(AGENDADO, REALIZADO), AGENDADO, false));
        // desmarcar o ultimo nao vale: pelo menos um fica marcado
        assertEquals(padrao, ConsultaAgendaView.marcar(padrao, AGENDADO, false));
        assertNotSame(padrao, ConsultaAgendaView.marcar(padrao, AGENDADO, false));   // nao altera o conjunto recebido
    }

    @Test
    void atalhoTodos() {
        assertEquals(EnumSet.allOf(StatusAtendimento.class), ConsultaAgendaView.marcar(EnumSet.of(FALTOU), null, true));
        // desmarcar "Todos" com tudo marcado volta ao padrao
        assertEquals(EnumSet.of(AGENDADO),
                ConsultaAgendaView.marcar(EnumSet.allOf(StatusAtendimento.class), null, false));
        // "Todos" desmarcado por consequencia (alguem desmarcou um status) mantem a selecao
        assertEquals(EnumSet.of(AGENDADO, CANCELADO),
                ConsultaAgendaView.marcar(EnumSet.of(AGENDADO, CANCELADO), null, false));
    }

    @Test
    void avisoDeQuemFicouForaDoConvite() {
        assertEquals("", ConsultaAgendaView.foraDoConvite(0));
        assertEquals("1 atendimento não entrou no convite (passado ou cancelado).", ConsultaAgendaView.foraDoConvite(1));
        assertEquals("3 atendimentos não entraram no convite (passados ou cancelados).", ConsultaAgendaView.foraDoConvite(3));
    }

    @Test
    void avisoDoPeriodoSoParaModeloPersonalizadoSemOMarcador() {
        assertFalse(PreferenciasDialog.precisaAvisarPeriodo(null));                     // usa o padrao novo
        assertTrue(PreferenciasDialog.precisaAvisarPeriodo("Oi {paciente}: {lista}"));
        assertFalse(PreferenciasDialog.precisaAvisarPeriodo("Sessões de {periodo}: {lista}"));
    }
}
