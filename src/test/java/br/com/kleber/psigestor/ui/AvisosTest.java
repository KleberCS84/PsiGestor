package br.com.kleber.psigestor.ui;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Textos de aviso que o usuario precisa ver (testados sem abrir janela JavaFX). */
class AvisosTest {

    /** M-7: a restauracao avisa de tudo que volta ao estado da data do backup. */
    @Test
    void avisoDeRestauracaoCitaSenhasUsuariosEAnonimizacoes() {
        String aviso = PrivacidadeView.AVISO_RESTAURACAO;
        assertTrue(aviso.contains("senhas"));
        assertTrue(aviso.contains("desativados"));
        assertTrue(aviso.contains("ATIVOS"));
        assertTrue(aviso.contains("anonimizados"));
    }

    /** M-6: a falha na partida diz onde esta a copia de antes da migracao e onde esta o detalhe tecnico. */
    @Test
    void mensagemDeFalhaNaPartidaApontaCopiaELogs() {
        Path pasta = Path.of("C:", "Users", "fulano", "PsiGestor");
        String msg = PsiGestorApp.mensagemFalhaAoAbrir(pasta);
        assertTrue(msg.contains(pasta.resolve("psigestor.db.pre-v6").toString()), msg);
        assertTrue(msg.contains(pasta.resolve("logs").toString()), msg);
        assertTrue(msg.contains("Nenhum dado foi apagado"), msg);
        assertTrue(msg.contains("versao mais antiga"), msg);
    }
}
