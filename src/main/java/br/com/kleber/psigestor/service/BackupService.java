package br.com.kleber.psigestor.service;

import br.com.kleber.psigestor.util.Criptografia;
import br.com.kleber.psigestor.util.Database;
import br.com.kleber.psigestor.util.LogTecnico;
import br.com.kleber.psigestor.util.RegraNegocioException;
import br.com.kleber.psigestor.util.Sessao;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.stream.Stream;

/**
 * Backup cifrado (.psgb) e restauracao. A copia e feita com "VACUUM INTO" num temporario
 * ao lado do banco, conferida com "PRAGMA integrity_check" e cifrada com AES-256-GCM a partir
 * de uma senha de backup pedida na hora (nao fica guardada em lugar nenhum).
 * O banco principal (psigestor.db) continua sem cifra: ver docs/POLITICA_DADOS.md (BitLocker).
 */
public class BackupService {
    public static final String EXTENSAO = ".psgb";
    private static final DateTimeFormatter CARIMBO = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Contexto ctx;
    private final Database db;

    BackupService(Contexto ctx, Database db) {
        this.ctx = ctx;
        this.db = db;
    }

    /** Gera o backup cifrado em {@code pasta}; a senha e pedida duas vezes (senha e confirmacao). */
    public Path gerar(Path pasta, char[] senha, char[] confirmacao) {
        try {
            exigirPsicologo("Backup restrito ao psicologo responsavel.");
            if (senha == null || senha.length < AutenticacaoService.SENHA_MINIMA)
                throw new RegraNegocioException("A senha do backup deve ter pelo menos "
                        + AutenticacaoService.SENHA_MINIMA + " caracteres.");
            if (!Arrays.equals(senha, confirmacao))
                throw new RegraNegocioException("As senhas do backup nao conferem.");
            Path destino;
            Path temp = null;
            try {
                // o texto puro nunca sai da pasta onde o .db (tambem em claro) ja esta
                temp = Files.createTempDirectory(db.arquivo().getParent(), "backup-");
                Path claro = temp.resolve("copia.db");
                try (Statement st = db.getConexao().createStatement()) {
                    st.execute("VACUUM INTO '" + claro.toString().replace("'", "''") + "'");
                }
                try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + claro);
                     Statement st = c.createStatement()) {
                    exigirIntegridade(st, "A copia do banco falhou na verificacao de integridade. Backup nao gerado.");
                }
                byte[] dados = Files.readAllBytes(claro);
                byte[] pacote = Criptografia.cifrarComSenha(dados, senha);
                Arrays.fill(dados, (byte) 0);
                Files.createDirectories(pasta);
                destino = pasta.resolve("psigestor_backup_" + carimbo() + "_" + sufixoAleatorio() + EXTENSAO)
                        .toAbsolutePath();
                Files.write(destino, pacote, StandardOpenOption.CREATE_NEW);   // nunca sobrescreve
            } catch (SQLException | IOException e) {
                // a mensagem do driver/SO traz caminhos: vai para o log tecnico, nao para a tela
                LogTecnico.erro("Falha no backup", e);
                throw new RegraNegocioException("Nao foi possivel gerar o backup. Verifique se a pasta permite "
                        + "gravacao e se ha espaco em disco.");
            } finally {
                apagar(temp);
            }
            // VACUUM INTO nao roda dentro de transacao: se o log falhar, o backup sem trilha e apagado
            try {
                ctx.auditar("BACKUP", null, null);
            } catch (RuntimeException e) {
                try { Files.deleteIfExists(destino); } catch (IOException ex) { e.addSuppressed(ex); }
                throw e;
            }
            return destino;
        } finally {
            limpar(senha);
            limpar(confirmacao);
        }
    }

    /**
     * Substitui o banco atual pelo conteudo do backup. Antes de tocar no banco: decifra, confere
     * integridade e versao do esquema. O banco atual e copiado para
     * "psigestor.db.antes-restauracao-&lt;data&gt;". Depois disso o contexto fica fechado:
     * o app precisa ser reiniciado. Devolve o caminho da copia do banco anterior.
     */
    public Path restaurar(Path arquivo, char[] senha) {
        try {
            Sessao s = exigirPsicologo("Restauracao restrita ao psicologo responsavel.");
            if (senha == null || senha.length == 0) throw new RegraNegocioException("Informe a senha do backup.");
            Path banco = db.arquivo();
            Path temp = null;
            try {
                byte[] pacote = ler(arquivo);
                byte[] claro = Criptografia.decifrarComSenha(pacote, senha);   // senha errada/adulterado para aqui
                Path novo;
                try {
                    temp = Files.createTempDirectory(banco.getParent(), "restauracao-");
                    novo = temp.resolve("restaurado.db");
                    Files.write(novo, claro, StandardOpenOption.CREATE_NEW);
                } catch (IOException e) {
                    LogTecnico.erro("Falha ao preparar restauracao", e);
                    throw new RegraNegocioException("Nao foi possivel preparar a restauracao. Verifique o espaco em disco.");
                } finally {
                    Arrays.fill(claro, (byte) 0);
                }
                validarERegistrar(novo, s.usuario().getLogin());

                // a partir daqui o banco atual e fechado (o Windows nao substitui arquivo aberto)
                ctx.auditar("RESTAURACAO_BACKUP", null, null);   // fica na copia "antes-restauracao"
                ctx.sair();
                ctx.close();
                Path copia = banco.resolveSibling(banco.getFileName() + ".antes-restauracao-"
                        + LocalDateTime.now().format(CARIMBO));
                try {
                    Files.copy(banco, copia);   // falha se ja existir: nunca sobrescreve
                    Files.move(novo, banco, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    LogTecnico.erro("Falha ao substituir o banco na restauracao", e);
                    throw new RegraNegocioException("Nao foi possivel substituir o banco; o banco atual foi mantido. "
                            + "Reinicie o PsiGestor.");
                }
                return copia;
            } finally {
                apagar(temp);
            }
        } finally {
            limpar(senha);
        }
    }

    private Sessao exigirPsicologo(String mensagem) {
        Sessao s = ctx.exigirSessao();
        if (!s.usuario().isPsicologo()) throw new RegraNegocioException(mensagem);
        return s;
    }

    private static byte[] ler(Path arquivo) {
        try {
            return Files.readAllBytes(arquivo);
        } catch (IOException e) {
            LogTecnico.erro("Falha ao ler backup", e);
            throw new RegraNegocioException("Nao foi possivel ler o arquivo de backup.");
        }
    }

    /**
     * Confere o banco decifrado (integridade, esquema do PsiGestor, versao nao mais nova que a do app)
     * e grava nele o evento de restauracao, para a trilha do banco restaurado tambem mostrar o fato.
     */
    private static void validarERegistrar(Path novo, String login) {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + novo);
             Statement st = c.createStatement()) {
            exigirIntegridade(st, "O backup falhou na verificacao de integridade. Nada foi alterado.");
            try (ResultSet rs = st.executeQuery("""
                    SELECT count(*) FROM sqlite_master
                     WHERE type = 'table' AND name IN ('usuario', 'paciente', 'log_acesso')""")) {
                if (!rs.next() || rs.getInt(1) != 3)
                    throw new RegraNegocioException("O arquivo nao contem um banco do PsiGestor. Nada foi alterado.");
            }
            int versao;
            try (ResultSet rs = st.executeQuery("PRAGMA user_version")) {
                versao = rs.next() ? rs.getInt(1) : 0;
            }
            // versao mais antiga e migrada pelo Database ao reiniciar; mais nova o app nao conhece
            if (versao > Database.VERSAO_ESQUEMA)
                throw new RegraNegocioException("Backup gerado por uma versao mais nova do PsiGestor. "
                        + "Atualize o programa antes de restaurar.");
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO log_acesso (usuario_id, acao)
                    VALUES ((SELECT id FROM usuario WHERE login = ?), 'RESTAURACAO_BACKUP')""")) {
                ps.setString(1, login);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            LogTecnico.erro("Backup decifrado nao e um banco valido", e);
            throw new RegraNegocioException("O arquivo nao contem um banco valido do PsiGestor. Nada foi alterado.");
        }
    }

    private static void exigirIntegridade(Statement st, String mensagem) throws SQLException {
        try (ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
            if (!rs.next() || !"ok".equalsIgnoreCase(rs.getString(1))) throw new RegraNegocioException(mensagem);
        }
    }

    private static String carimbo() { return LocalDateTime.now().format(CARIMBO); }

    /** Nome nao previsivel e sem colisao mesmo com dois arquivos no mesmo segundo. */
    static String sufixoAleatorio() {
        byte[] b = new byte[4];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /**
     * Na partida: apaga temporarios "backup-*" e "restauracao-*" deixados por um backup ou restauracao
     * interrompidos (queda de energia, processo morto). Contem copia do banco em claro. Nunca lanca.
     */
    public static void limparTemporarios(Path pasta) {
        if (!Files.isDirectory(pasta)) return;
        try (Stream<Path> itens = Files.list(pasta)) {
            itens.filter(Files::isDirectory)
                 .filter(p -> {
                     String nome = p.getFileName().toString();
                     return nome.startsWith("backup-") || nome.startsWith("restauracao-");
                 })
                 .toList()
                 .forEach(BackupService::apagar);
        } catch (IOException e) {
            LogTecnico.erro("Falha ao listar temporarios de backup", e);
        }
    }

    /** Apaga o diretorio temporario e tudo dentro (contem copia do banco em claro). */
    private static void apagar(Path dir) {
        if (dir == null) return;
        try (Stream<Path> arquivos = Files.walk(dir)) {
            for (Path p : arquivos.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException e) {
            LogTecnico.erro("Temporario de backup nao foi apagado", e);
        }
    }

    private static void limpar(char[] senha) {
        if (senha != null) Arrays.fill(senha, '\0');
    }
}
