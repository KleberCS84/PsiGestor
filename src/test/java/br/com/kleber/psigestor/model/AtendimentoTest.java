package br.com.kleber.psigestor.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class AtendimentoTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2030, 3, 4, 10, 0);

    private Atendimento das10as11() {
        Atendimento a = new Atendimento();
        a.setInicio(BASE);
        a.setFim(BASE.plusMinutes(60));
        return a;
    }

    @Test
    void sobreposicaoParcialConflita() {
        assertTrue(das10as11().conflitaCom(BASE.plusMinutes(30), BASE.plusMinutes(90)));
        assertTrue(das10as11().conflitaCom(BASE.minusMinutes(30), BASE.plusMinutes(30)));
    }

    @Test
    void intervaloContidoOuQueContemConflita() {
        assertTrue(das10as11().conflitaCom(BASE.plusMinutes(10), BASE.plusMinutes(20)));
        assertTrue(das10as11().conflitaCom(BASE.minusMinutes(10), BASE.plusMinutes(120)));
    }

    @Test
    void horariosEncostadosNaoConflitam() {
        assertFalse(das10as11().conflitaCom(BASE.plusMinutes(60), BASE.plusMinutes(110)));
        assertFalse(das10as11().conflitaCom(BASE.minusMinutes(50), BASE));
    }

    @Test
    void horariosDistantesNaoConflitam() {
        assertFalse(das10as11().conflitaCom(BASE.plusHours(5), BASE.plusHours(6)));
    }

    @Test
    void padroesDeUmNovoAtendimento() {
        Atendimento a = new Atendimento();
        assertEquals(StatusAtendimento.AGENDADO, a.getStatus());
        assertEquals(Modalidade.PRESENCIAL, a.getModalidade());
        assertFalse(a.isPago());
        assertFalse(a.isReciboEmitido());
    }

    @Test
    void somenteCanceladoLiberaOHorario() {
        for (StatusAtendimento s : StatusAtendimento.values())
            assertEquals(s != StatusAtendimento.CANCELADO, s.ocupaHorario(), s.name());
    }
}
