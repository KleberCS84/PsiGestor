package br.com.kleber.psigestor.ui;

import br.com.kleber.psigestor.dao.DaoException;
import br.com.kleber.psigestor.util.RegraNegocioException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Tratamento de erro do Ui.executar (sem toolkit JavaFX: o Alert e trocado por um coletor). */
class UiTest {
    private Consumer<String> original;
    private final List<String> exibidos = new ArrayList<>();

    @BeforeEach
    void trocarJanela() {
        original = Ui.exibirErro;
        Ui.exibirErro = exibidos::add;
    }

    @AfterEach
    void restaurarJanela() { Ui.exibirErro = original; }

    @Test
    void regraDeNegocioMostraAPropriaMensagem() {
        assertEquals("CPF invalido.", Ui.mensagemPara(new RegraNegocioException("CPF invalido.")));
    }

    /** Antes: "Erro ao salvar paciente: FOREIGN KEY constraint failed" ia direto para a tela. */
    @Test
    void erroDeBancoMostraMensagemGenerica() {
        String msg = Ui.mensagemPara(new DaoException("Erro ao salvar paciente",
                new SQLException("FOREIGN KEY constraint failed")));
        assertEquals(Ui.ERRO_GENERICO, msg);
        assertFalse(msg.contains("FOREIGN KEY"));
    }

    @Test
    void erroInesperadoNaoExpoeDetalheInterno() {
        assertFalse(Ui.executar(() -> { throw new NullPointerException("campo interno nulo"); }));
        assertEquals(List.of(Ui.ERRO_GENERICO), exibidos);
    }

    @Test
    void acaoSemErroNaoMostraNada() {
        assertTrue(Ui.executar(() -> { }));
        assertTrue(exibidos.isEmpty());
    }
}
