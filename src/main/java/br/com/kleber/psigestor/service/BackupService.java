package br.com.kleber.psigestor.service;

import br.com.kleber.psigestor.util.LogTecnico;
import br.com.kleber.psigestor.util.RegraNegocioException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Copia consistente do banco usando "VACUUM INTO" do SQLite.
 * O prontuario continua cifrado no backup, mas os dados cadastrais nao:
 * guarde o arquivo em local protegido (ver docs/PONTOS_DE_ATENCAO_LEGAIS.md).
 */
public class BackupService {
    private final Contexto ctx;
    private final Connection con;

    BackupService(Contexto ctx, Connection con) {
        this.ctx = ctx;
        this.con = con;
    }

    public Path gerar(Path pasta) {
        var s = ctx.exigirSessao();
        if (!s.usuario().isPsicologo()) throw new RegraNegocioException("Backup restrito ao psicologo responsavel.");
        Path destino;
        try {
            Files.createDirectories(pasta);
            String nome = "psigestor_backup_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".db";
            destino = pasta.resolve(nome).toAbsolutePath();
            try (Statement st = con.createStatement()) {
                st.execute("VACUUM INTO '" + destino.toString().replace("'", "''") + "'");
            }
        } catch (SQLException | IOException e) {
            // a mensagem do driver/SO traz caminhos: vai para o log tecnico, nao para a tela
            LogTecnico.erro("Falha no backup", e);
            throw new RegraNegocioException("Nao foi possivel gerar o backup. Verifique se a pasta permite "
                    + "gravacao e se ha espaco em disco.");
        }
        // VACUUM INTO nao roda dentro de transacao: se o log falhar, o backup sem trilha e apagado
        try {
            ctx.auditar("BACKUP", null, null);
        } catch (RuntimeException e) {
            try { Files.deleteIfExists(destino); } catch (IOException ex) { e.addSuppressed(ex); }
            throw e;
        }
        return destino;
    }
}
