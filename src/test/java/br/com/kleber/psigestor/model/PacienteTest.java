package br.com.kleber.psigestor.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class PacienteTest {

    private Paciente nascidoEm(LocalDate d) { return new Paciente("Fulano", d, "62999990000"); }

    @Test
    void idadeContaAnosCompletos() {
        Paciente p = nascidoEm(LocalDate.of(2000, 6, 15));
        assertEquals(24, p.idadeEm(LocalDate.of(2025, 6, 14)));
        assertEquals(25, p.idadeEm(LocalDate.of(2025, 6, 15)));
    }

    @Test
    void semDataDeNascimentoIdadeEhNulaENaoEhMenor() {
        Paciente p = nascidoEm(null);
        assertNull(p.idadeEm(LocalDate.now()));
        assertFalse(p.isMenorDeIdade(LocalDate.now()));
    }

    @Test
    void menoridadeMudaNoDia18Anos() {
        Paciente p = nascidoEm(LocalDate.of(2010, 8, 10));
        assertTrue(p.isMenorDeIdade(LocalDate.of(2028, 8, 9)));
        assertFalse(p.isMenorDeIdade(LocalDate.of(2028, 8, 10)));
    }

    @Test
    void aniversarioIgnoraOAno() {
        Paciente p = nascidoEm(LocalDate.of(1990, 5, 20));
        assertTrue(p.fazAniversarioEm(LocalDate.of(2031, 5, 20)));
        assertFalse(p.fazAniversarioEm(LocalDate.of(2031, 5, 21)));
        assertFalse(nascidoEm(null).fazAniversarioEm(LocalDate.of(2031, 5, 20)));
    }
}
