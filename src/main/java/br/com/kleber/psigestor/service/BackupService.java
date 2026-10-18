package br.com.kleber.psigestor.service;

import br.com.kleber.psigestor.util.RegraNegocioException;

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
        try {
            Files.createDirectories(pasta);
            String nome = "psigestor_backup_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".db";
            Path destino = pasta.resolve(nome).toAbsolutePath();
            try (Statement st = con.createStatement()) {
                st.execute("VACUUM INTO '" + destino.toString().replace("'", "''") + "'");
            }
            ctx.auditar("BACKUP", null, null);
            return destino;
        } catch (SQLException | java.io.IOException e) {
            throw new RegraNegocioException("Falha no backup: " + e.getMessage());
        }
    }
}
