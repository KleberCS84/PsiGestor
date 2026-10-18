package br.com.kleber.psigestor.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FinanceiroResumoTest {

    @Test
    void taxaDeFaltasEhZeroSemSessoesEvitandoDivisaoPorZero() {
        assertEquals(0.0, new FinanceiroService.Resumo(0, 0, 0, 0, 0, 3, 0, 0, 0).taxaFaltas());
    }

    @Test
    void taxaDeFaltasConsideraRealizadasMaisFaltasEIgnoraCanceladas() {
        var r = new FinanceiroService.Resumo(0, 0, 0, 3, 1, 10, 0, 0, 0);
        assertEquals(0.25, r.taxaFaltas(), 1e-9);
    }

    @Test
    void todasFaltasDaTaxaCem() {
        assertEquals(1.0, new FinanceiroService.Resumo(0, 0, 0, 0, 2, 0, 0, 0, 0).taxaFaltas(), 1e-9);
    }
}
