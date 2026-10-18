package br.com.kleber.psigestor.util;

import br.com.kleber.psigestor.dao.DaoException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class LogTecnicoTest {

    @TempDir Path tmp;

    @AfterEach
    void fechar() { LogTecnico.encerrar(); }   // libera o arquivo para o @TempDir ser apagado

    /** Mensagem de excecao pode carregar dado de paciente: no log vai so classe e pilha. */
    @Test
    void descricaoNaoTemMensagemMasTemPilha() {
        var causa = new IllegalArgumentException("Paciente Joao Lima, CPF 52998224725");
        String d = LogTecnico.descrever(new RuntimeException("falha com Joao Lima", causa));
        assertFalse(d.contains("Joao Lima"));
        assertFalse(d.contains("52998224725"));
        assertTrue(d.contains("java.lang.RuntimeException"));
        assertTrue(d.contains("Causado por: java.lang.IllegalArgumentException"));
        assertTrue(d.contains("LogTecnicoTest.descricaoNaoTemMensagemMasTemPilha"));
    }

    @Test
    void mensagemDoDriverSqlFica() {
        String d = LogTecnico.descrever(new DaoException("Erro ao salvar paciente",
                new SQLException("FOREIGN KEY constraint failed")));
        assertTrue(d.contains("java.sql.SQLException: FOREIGN KEY constraint failed"));
    }

    @Test
    void gravaEmArquivoNaPastaInformada() throws IOException {
        Path pasta = tmp.resolve("logs");
        LogTecnico.iniciar(pasta);
        LogTecnico.erro("Teste de gravacao", new IllegalStateException("texto do prontuario"));
        Path arq = pasta.resolve("psigestor-0.log");
        assertTrue(Files.exists(arq));
        String conteudo = Files.readString(arq, StandardCharsets.UTF_8);
        assertTrue(conteudo.contains("Teste de gravacao"));
        assertTrue(conteudo.contains("java.lang.IllegalStateException"));
        assertFalse(conteudo.contains("texto do prontuario"));
    }
}
