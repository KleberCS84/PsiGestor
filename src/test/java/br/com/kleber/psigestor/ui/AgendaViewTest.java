package br.com.kleber.psigestor.ui;

import br.com.kleber.psigestor.util.RegraNegocioException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/** So a montagem de data/hora do dialogo (metodo estatico, nao precisa do toolkit JavaFX). */
class AgendaViewTest {

    /** Antes: DatePicker limpo dava NPE em data.getValue().atTime(h), exibido como "Erro inesperado: null". */
    @Test
    void dataNulaViraMensagemDeRegra() {
        assertThrows(RegraNegocioException.class, () -> AgendaView.montarInicio(null, "08:00"));
    }

    @Test
    void horaInvalidaERecusada() {
        assertThrows(RegraNegocioException.class, () -> AgendaView.montarInicio(LocalDate.of(2026, 10, 13), "25:00"));
        assertThrows(RegraNegocioException.class, () -> AgendaView.montarInicio(LocalDate.of(2026, 10, 13), null));
    }

    @Test
    void dataEHoraValidas() {
        assertEquals(LocalDateTime.of(2026, 10, 13, 14, 30), AgendaView.montarInicio(LocalDate.of(2026, 10, 13), " 14:30 "));
    }
}
