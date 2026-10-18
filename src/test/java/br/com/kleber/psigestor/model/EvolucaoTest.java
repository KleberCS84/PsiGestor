package br.com.kleber.psigestor.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class EvolucaoTest {

    @Test
    void toStringNaoExpoeConteudoDoProntuario() {
        Evolucao e = new Evolucao(1, 2, null, 3, LocalDateTime.of(2026, 10, 13, 14, 0), "relato sigiloso", null);
        assertFalse(e.toString().contains("relato sigiloso"));
    }
}
