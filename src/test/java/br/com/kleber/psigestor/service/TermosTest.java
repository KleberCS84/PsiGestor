package br.com.kleber.psigestor.service;

import br.com.kleber.psigestor.model.LogAcesso;
import br.com.kleber.psigestor.model.Papel;
import br.com.kleber.psigestor.model.Usuario;
import br.com.kleber.psigestor.util.Database;
import br.com.kleber.psigestor.util.Termos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

/** Termos de uso: aceite por usuario, versao e data gravadas, trilha TERMOS_ACEITOS, migracao v8. */
class TermosTest {

    private static final LocalDateTime AGORA = LocalDateTime.of(2026, 10, 12, 9, 30, 15);

    @TempDir Path tmp;
    Contexto ctx;
    Usuario ana;

    @BeforeEach
    void preparar() {
        ctx = new Contexto(url());
        ZoneId zona = ZoneId.systemDefault();
        ctx.setRelogio(Clock.fixed(AGORA.atZone(zona).toInstant(), zona));
        ana = ctx.autenticacao.cadastrar("Ana Souza", "ana", "senhaForte12".toCharArray(),
                Papel.PSICOLOGO, "09/12345", 30);
        ctx.autenticacao.entrar("ana", "senhaForte12".toCharArray());
    }

    @AfterEach
    void fechar() { ctx.close(); }

    private String url() { return "jdbc:sqlite:" + tmp.resolve("teste.db"); }

    @Test
    void usuarioNovoPrecisaAceitar() {
        assertEquals(0, ctx.usuarios.buscar(ana.getId()).orElseThrow().getTermosVersao());
        assertTrue(ctx.autenticacao.precisaAceitarTermos());
    }

    @Test
    void aceiteGravaVersaoDataETrilha() {
        ctx.autenticacao.aceitarTermos();

        Usuario u = ctx.usuarios.buscar(ana.getId()).orElseThrow();
        assertEquals(Termos.VERSAO_ATUAL, u.getTermosVersao());
        assertEquals("2026-10-12T09:30:15", u.getTermosAceitosEm());
        LogAcesso log = ctx.logs.ultimos(1).get(0);
        assertEquals("TERMOS_ACEITOS", log.acao());
        assertEquals("v" + Termos.VERSAO_ATUAL, log.detalhe());
        assertEquals(ana.getId(), log.entidadeId());
        assertFalse(ctx.autenticacao.precisaAceitarTermos());
    }

    @Test
    void aceiteValeParaOProximoLogin() {
        ctx.autenticacao.aceitarTermos();
        ctx.sair();
        ctx.autenticacao.entrar("ana", "senhaForte12".toCharArray());
        assertFalse(ctx.autenticacao.precisaAceitarTermos());
    }

    /** Termos mudaram (versao nova): quem aceitou a anterior precisa aceitar de novo. */
    @Test
    void novaVersaoPedeNovoAceite() {
        ctx.autenticacao.aceitarTermos();
        assertFalse(ctx.autenticacao.precisaAceitarTermos(Termos.VERSAO_ATUAL));
        assertTrue(ctx.autenticacao.precisaAceitarTermos(Termos.VERSAO_ATUAL + 1));
    }

    /** O aceite e de cada pessoa: o da psicologa nao vale para a secretaria. */
    @Test
    void secretariaTambemPrecisaAceitar() {
        ctx.autenticacao.aceitarTermos();
        ctx.autenticacao.cadastrar("Bia Recepcao", "bia", "senhaBia1234".toCharArray(), Papel.SECRETARIA, null, 0);
        ctx.sair();
        ctx.autenticacao.entrar("bia", "senhaBia1234".toCharArray());
        assertTrue(ctx.autenticacao.precisaAceitarTermos());
    }

    @Test
    void semLoginNaoConsultaNemAceita() {
        ctx.sair();
        assertThrows(RuntimeException.class, () -> ctx.autenticacao.precisaAceitarTermos());
        assertThrows(RuntimeException.class, () -> ctx.autenticacao.aceitarTermos());
    }

    /** Banco v7 (sem as colunas) abre na v8: copia pre-v8 e usuarios existentes ainda sem aceite. */
    @Test
    void migracaoV8AcrescentaColunasSemAceite() throws SQLException {
        ctx.close();
        try (Connection con = DriverManager.getConnection(url()); Statement st = con.createStatement()) {
            st.execute("ALTER TABLE usuario DROP COLUMN termos_versao");
            st.execute("ALTER TABLE usuario DROP COLUMN termos_aceitos_em");
            st.execute("PRAGMA user_version = 7");
        }
        ctx = new Contexto(url());
        assertTrue(Files.exists(tmp.resolve("teste.db.pre-v" + Database.VERSAO_ESQUEMA)));
        try (Connection con = DriverManager.getConnection(url()); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            assertEquals(8, rs.getInt(1));
        }
        Usuario u = ctx.usuarios.buscar(ana.getId()).orElseThrow();
        assertEquals(0, u.getTermosVersao());
        assertNull(u.getTermosAceitosEm());
    }

    @Test
    void textoDosTermosCobreOsPontosObrigatorios() {
        String texto = Termos.texto();
        assertTrue(texto.contains("\"como esta\""));
        assertTrue(texto.contains("Backup e responsabilidade do usuario"));
        assertTrue(texto.contains("nao tem acesso aos dados"));
        assertTrue(texto.contains("CFP"));
        assertTrue(texto.contains("MIT"));
    }
}
