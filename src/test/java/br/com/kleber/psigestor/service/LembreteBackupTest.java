package br.com.kleber.psigestor.service;

import br.com.kleber.psigestor.model.Papel;
import br.com.kleber.psigestor.model.Usuario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.*;

/** Lembrete de backup no Painel: ultimo evento BACKUP da trilha com mais de 7 dias, ou nenhum. */
class LembreteBackupTest {

    /** "Agora" fixo dos testes: 12/10/2026 10:00. */
    private static final LocalDateTime AGORA = LocalDateTime.of(2026, 10, 12, 10, 0);
    /** Formato de log_acesso.data_hora (datetime('now','localtime') do SQLite). */
    private static final DateTimeFormatter DATA_HORA_LOG = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @TempDir Path tmp;
    Contexto ctx;
    Usuario ana;

    @BeforeEach
    void preparar() {
        ctx = new Contexto("jdbc:sqlite:" + tmp.resolve("teste.db"));
        ZoneId zona = ZoneId.systemDefault();
        ctx.setRelogio(Clock.fixed(AGORA.atZone(zona).toInstant(), zona));
        ana = ctx.autenticacao.cadastrar("Ana Souza", "ana", "senhaForte12".toCharArray(),
                Papel.PSICOLOGO, "09/12345", 30);
        ctx.autenticacao.entrar("ana", "senhaForte12".toCharArray());
    }

    @AfterEach
    void fechar() { ctx.close(); }

    /** A trilha so aceita INSERT; data_hora explicita simula um evento no passado. */
    private void registrarNaTrilha(String acao, LocalDateTime quando) throws SQLException {
        try (PreparedStatement ps = ctx.getConexaoParaTeste().prepareStatement(
                "INSERT INTO log_acesso (usuario_id, acao, data_hora) VALUES (?, ?, ?)")) {
            ps.setInt(1, ana.getId());
            ps.setString(2, acao);
            ps.setString(3, quando.format(DATA_HORA_LOG));
            ps.executeUpdate();
        }
    }

    @Test
    void semNenhumBackupLembra() {
        assertTrue(ctx.backup.ultimoBackup().isEmpty());
        assertTrue(ctx.backup.precisaLembrete());
    }

    @Test
    void backupRecenteNaoLembra() throws SQLException {
        registrarNaTrilha("BACKUP", AGORA.minusDays(3));
        assertEquals(AGORA.minusDays(3), ctx.backup.ultimoBackup().orElseThrow());
        assertFalse(ctx.backup.precisaLembrete());
    }

    @Test
    void exatamenteSeteDiasAindaNaoLembra() throws SQLException {
        registrarNaTrilha("BACKUP", AGORA.minusDays(7));
        assertFalse(ctx.backup.precisaLembrete());
    }

    @Test
    void maisDeSeteDiasLembra() throws SQLException {
        registrarNaTrilha("BACKUP", AGORA.minusDays(7).minusMinutes(1));
        assertTrue(ctx.backup.precisaLembrete());
    }

    @Test
    void valeOBackupMaisRecente() throws SQLException {
        registrarNaTrilha("BACKUP", AGORA.minusDays(30));
        registrarNaTrilha("BACKUP", AGORA.minusDays(1));
        assertEquals(AGORA.minusDays(1), ctx.backup.ultimoBackup().orElseThrow());
        assertFalse(ctx.backup.precisaLembrete());
    }

    @Test
    void restauracaoNaoContaComoBackup() throws SQLException {
        registrarNaTrilha("RESTAURACAO_BACKUP", AGORA.minusDays(1));
        assertTrue(ctx.backup.ultimoBackup().isEmpty());
        assertTrue(ctx.backup.precisaLembrete());
    }

    /** Fluxo real: o evento BACKUP gravado por gerar() desliga o lembrete. */
    @Test
    void backupGeradoDesligaOLembrete() {
        // data_hora do log vem do relogio real do SQLite; relogio novo = sessao vencida, entra de novo
        ctx.setRelogio(Clock.systemDefaultZone());
        ctx.autenticacao.entrar("ana", "senhaForte12".toCharArray());
        assertTrue(ctx.backup.precisaLembrete());
        ctx.backup.gerar(tmp.resolve("backups"), "senhaBackup123".toCharArray(), "senhaBackup123".toCharArray());
        assertTrue(ctx.backup.ultimoBackup().isPresent());
        assertFalse(ctx.backup.precisaLembrete());
    }

    /** Secretaria nao faz backup: nunca ve o lembrete, mesmo sem backup algum. */
    @Test
    void secretariaNaoRecebeLembrete() {
        ctx.autenticacao.cadastrar("Bia Recepcao", "bia", "senhaBia1234".toCharArray(), Papel.SECRETARIA, null, 0);
        ctx.sair();
        ctx.autenticacao.entrar("bia", "senhaBia1234".toCharArray());
        assertFalse(ctx.backup.precisaLembrete());
    }
}
