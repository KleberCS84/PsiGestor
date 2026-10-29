package br.com.kleber.psigestor.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Mantem UMA conexao JDBC com o arquivo SQLite (app desktop de usuario unico por vez).
 * Use "jdbc:sqlite::memory:" nos testes.
 */
public class Database implements AutoCloseable {

    /**
     * Versao do esquema, gravada dentro do proprio arquivo .db (PRAGMA user_version).
     * O schema.sql usa "CREATE TABLE IF NOT EXISTS", que NAO acrescenta colunas novas
     * em um banco que ja existe. Por isso cada mudanca de estrutura vira um passo em migrar().
     *  v1: usuario.chave_cifrada + usuario.chave_iv (criptografia de envelope / troca de senha)
     *  v2: convenio.prazo_pagamento_dias >= 0 (triggers: SQLite nao acrescenta CHECK em tabela existente)
     *  v3: log_acesso.detalhe (login tentado nas falhas de login)
     *  v4: usuario.iteracoes (contagem do PBKDF2 por usuario; existentes = 210000)
     *  v5: dinheiro em centavos: convenio.valor_sessao, servico.valor, atendimento.valor REAL -> INTEGER
     *  v6: atendimento.percentual_repasse_clinica (congelado no REALIZADO; existentes = percentual atual)
     *      e paciente.anonimizado_em
     *  v7: usuario.dek_recuperacao, salt_recuperacao, iteracoes_recuperacao (codigo de recuperacao; nulos)
     *  v8: usuario.termos_versao (0 = nunca aceitou) e usuario.termos_aceitos_em (aceite dos termos de uso)
     *  v9: usuario.minutos_inatividade (existentes = 15) e atendimento.observacao
     * Antes de migrar um banco com dados, uma copia fica em "psigestor.db.pre-v<VERSAO_ESQUEMA>".
     */
    public static final int VERSAO_ESQUEMA = 9;
    private static final String PREFIXO_URL = "jdbc:sqlite:";

    private final String url;
    private final Connection conexao;

    public Database(String url) {
        this.url = url;
        try {
            conexao = DriverManager.getConnection(url);
        } catch (SQLException e) {
            throw new IllegalStateException("Nao foi possivel abrir o banco: " + e.getMessage(), e);
        }
        try {
            try (Statement st = conexao.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");   // SQLite vem com FK desligada!
            }
            criarEsquema();
            migrar();
        } catch (SQLException | RuntimeException e) {
            // conexao aberta prenderia o arquivo (no Windows nem a copia/restauracao manual funcionaria)
            try { conexao.close(); } catch (SQLException c) { e.addSuppressed(c); }
            throw new IllegalStateException("Nao foi possivel abrir o banco: " + e.getMessage(), e);
        }
    }

    public Connection getConexao() { return conexao; }

    /** Caminho do arquivo .db (backup e restauracao trabalham ao lado dele). */
    public Path arquivo() {
        String resto = url.startsWith(PREFIXO_URL) ? url.substring(PREFIXO_URL.length()) : "";
        if (resto.isBlank() || resto.startsWith(":memory:"))
            throw new IllegalStateException("Banco em memoria nao tem arquivo.");
        return Path.of(resto).toAbsolutePath();
    }

    private void criarEsquema() throws SQLException {
        try (Statement st = conexao.createStatement()) {
            for (String comando : lerComandos("/db/schema.sql")) {
                st.execute(comando);
            }
        }
    }

    private void migrar() throws SQLException {
        int versao;
        try (Statement st = conexao.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            versao = rs.next() ? rs.getInt(1) : 0;
        }
        if (versao >= VERSAO_ESQUEMA) return;
        copiarAntesDeMigrar(versao);

        // v5 recria tabelas referenciadas por FK; o pragma so tem efeito fora de transacao
        try (Statement st = conexao.createStatement()) { st.execute("PRAGMA foreign_keys = OFF"); }
        conexao.setAutoCommit(false);   // ou a migracao inteira entra, ou nada entra
        try (Statement st = conexao.createStatement()) {
            if (versao < 1) {
                adicionarColunaSeFaltar(st, "usuario", "chave_cifrada", "BLOB");
                adicionarColunaSeFaltar(st, "usuario", "chave_iv", "BLOB");
            }
            if (versao < 2) {
                // banco novo ja tem o CHECK (schema.sql); banco antigo so pode ganhar a regra via trigger
                st.execute("""
                    CREATE TRIGGER IF NOT EXISTS trg_convenio_prazo_ins BEFORE INSERT ON convenio
                    WHEN NEW.prazo_pagamento_dias < 0
                    BEGIN SELECT RAISE(ABORT, 'Prazo de pagamento nao pode ser negativo.'); END""");
                st.execute("""
                    CREATE TRIGGER IF NOT EXISTS trg_convenio_prazo_upd BEFORE UPDATE OF prazo_pagamento_dias ON convenio
                    WHEN NEW.prazo_pagamento_dias < 0
                    BEGIN SELECT RAISE(ABORT, 'Prazo de pagamento nao pode ser negativo.'); END""");
            }
            if (versao < 3) {
                adicionarColunaSeFaltar(st, "log_acesso", "detalhe", "TEXT");
            }
            if (versao < 4) {
                // usuarios ja cadastrados foram derivados com 210.000; o login seguinte migra para o atual
                adicionarColunaSeFaltar(st, "usuario", "iteracoes", "INTEGER NOT NULL DEFAULT 210000");
            }
            if (versao < 5) {
                converterParaCentavos(st, "convenio", "valor_sessao");
                converterParaCentavos(st, "servico", "valor");
                if (converterParaCentavos(st, "atendimento", "valor"))
                    st.execute("CREATE INDEX IF NOT EXISTS idx_atendimento_inicio ON atendimento(inicio)");
            }
            if (versao < 6) {
                // sem historico de vigencia: o melhor palpite para o passado e o percentual de hoje
                adicionarColunaSeFaltar(st, "atendimento", "percentual_repasse_clinica", "REAL");
                st.execute("""
                    UPDATE atendimento SET percentual_repasse_clinica =
                           (SELECT u.percentual_repasse_clinica FROM usuario u WHERE u.id = atendimento.profissional_id)
                     WHERE percentual_repasse_clinica IS NULL""");
                adicionarColunaSeFaltar(st, "paciente", "anonimizado_em", "TEXT");
            }
            if (versao < 7) {
                // nulos: o psicologo gera o codigo no proximo login (a DEK so existe com a senha dele)
                adicionarColunaSeFaltar(st, "usuario", "dek_recuperacao", "BLOB");
                adicionarColunaSeFaltar(st, "usuario", "salt_recuperacao", "TEXT");
                adicionarColunaSeFaltar(st, "usuario", "iteracoes_recuperacao", "INTEGER");
            }
            if (versao < 8) {
                // 0: quem ja usava o sistema aceita os termos no proximo login
                adicionarColunaSeFaltar(st, "usuario", "termos_versao", "INTEGER NOT NULL DEFAULT 0");
                adicionarColunaSeFaltar(st, "usuario", "termos_aceitos_em", "TEXT");
            }
            if (versao < 9) {
                // sem CHECK em tabela existente (como na v2): AutenticacaoService.alterarInatividade valida
                adicionarColunaSeFaltar(st, "usuario", "minutos_inatividade", "INTEGER NOT NULL DEFAULT 15");
                adicionarColunaSeFaltar(st, "atendimento", "observacao", "TEXT");
            }
            try (ResultSet rs = st.executeQuery("PRAGMA foreign_key_check")) {
                if (rs.next()) throw new SQLException("Migracao deixou referencia invalida em " + rs.getString(1));
            }
            st.execute("PRAGMA user_version = " + VERSAO_ESQUEMA);
            conexao.commit();
        } catch (SQLException e) {
            conexao.rollback();
            throw e;
        } finally {
            conexao.setAutoCommit(true);
            try (Statement st = conexao.createStatement()) { st.execute("PRAGMA foreign_keys = ON"); }
        }
    }

    /**
     * SQLite nao muda o tipo de coluna: recria a tabela com a definicao atual do schema.sql
     * (unica fonte da verdade) e copia os dados, reais -> centavos. Ids preservados, entao as FKs
     * continuam validas. Banco novo ja nasce INTEGER e nao passa por aqui. Devolve se converteu.
     */
    private boolean converterParaCentavos(Statement st, String tabela, String coluna) throws SQLException {
        List<String> colunas = new ArrayList<>();
        boolean real = false;
        try (ResultSet rs = st.executeQuery("PRAGMA table_info(" + tabela + ")")) {
            while (rs.next()) {
                String nome = rs.getString("name");
                colunas.add(nome);
                if (nome.equalsIgnoreCase(coluna)) real = rs.getString("type").equalsIgnoreCase("REAL");
            }
        }
        if (!real) return false;

        String prefixo = "CREATE TABLE IF NOT EXISTS " + tabela + " (";
        String definicao = lerComandos("/db/schema.sql").stream()
                .map(String::trim)
                .filter(c -> c.startsWith(prefixo))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Tabela fora do schema.sql: " + tabela));
        String nova = tabela + "_v5";
        st.execute("CREATE TABLE " + nova + " (" + definicao.substring(prefixo.length()));

        String lista = String.join(", ", colunas);
        String origem = String.join(", ", colunas.stream()
                .map(c -> c.equalsIgnoreCase(coluna) ? "CAST(ROUND(" + c + " * 100) AS INTEGER)" : c)
                .toList());
        st.execute("INSERT INTO " + nova + " (" + lista + ") SELECT " + origem + " FROM " + tabela);
        st.execute("DROP TABLE " + tabela);
        st.execute("ALTER TABLE " + nova + " RENAME TO " + tabela);
        return true;
    }

    /**
     * Migracoes mudam dados de forma irreversivel (ex.: v5 reais -> centavos): antes, uma copia
     * integra do banco como estava. Banco novo (sem usuario) nao precisa; em memoria nao ha arquivo.
     * Copia ja existente (tentativa anterior que falhou) e mantida: e o estado mais antigo.
     */
    private void copiarAntesDeMigrar(int versao) throws SQLException {
        if (!emArquivo()) return;
        if (versao == 0) {
            try (Statement st = conexao.createStatement();
                 ResultSet rs = st.executeQuery("SELECT EXISTS (SELECT 1 FROM usuario)")) {
                if (!rs.next() || rs.getInt(1) == 0) return;
            }
        }
        Path copia = arquivoDaCopiaPreMigracao();
        if (Files.exists(copia)) return;
        try (Statement st = conexao.createStatement()) {
            st.execute("VACUUM INTO '" + copia.toString().replace("'", "''") + "'");
        }
    }

    /** "psigestor.db.pre-v6": copia do banco anterior a migracao para a versao atual. */
    public Path arquivoDaCopiaPreMigracao() {
        Path banco = arquivo();
        return banco.resolveSibling(banco.getFileName() + ".pre-v" + VERSAO_ESQUEMA);
    }

    private boolean emArquivo() {
        String resto = url.startsWith(PREFIXO_URL) ? url.substring(PREFIXO_URL.length()) : "";
        return !resto.isBlank() && !resto.startsWith(":memory:");
    }

    /** Banco novo ja nasce com a coluna (schema.sql); banco antigo ganha a coluna aqui. */
    private void adicionarColunaSeFaltar(Statement st, String tabela, String coluna, String tipo) throws SQLException {
        try (ResultSet rs = st.executeQuery("PRAGMA table_info(" + tabela + ")")) {
            while (rs.next()) {
                if (rs.getString("name").equalsIgnoreCase(coluna)) return;
            }
        }
        st.execute("ALTER TABLE " + tabela + " ADD COLUMN " + coluna + " " + tipo);
    }

    /** Divide o script: comando comum termina em ";", trigger termina em "END;". */
    static List<String> lerComandos(String recurso) {
        List<String> comandos = new ArrayList<>();
        try (InputStream in = Database.class.getResourceAsStream(recurso)) {
            if (in == null) throw new IllegalStateException("Recurso nao encontrado: " + recurso);
            BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder atual = new StringBuilder();
            String linha;
            while ((linha = br.readLine()) != null) {
                String t = linha.trim();
                if (t.isEmpty() || t.startsWith("--")) continue;
                atual.append(linha).append('\n');
                boolean emTrigger = atual.toString().trim().toUpperCase().startsWith("CREATE TRIGGER");
                boolean fim = emTrigger ? t.equalsIgnoreCase("END;") : t.endsWith(";");
                if (fim) {
                    comandos.add(atual.toString());
                    atual.setLength(0);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return comandos;
    }

    @Override
    public void close() {
        try { conexao.close(); } catch (SQLException ignored) { }
    }
}
