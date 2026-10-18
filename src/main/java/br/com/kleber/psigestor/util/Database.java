package br.com.kleber.psigestor.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
     */
    static final int VERSAO_ESQUEMA = 2;

    private final Connection conexao;

    public Database(String url) {
        try {
            conexao = DriverManager.getConnection(url);
            try (Statement st = conexao.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");   // SQLite vem com FK desligada!
            }
            criarEsquema();
            migrar();
        } catch (SQLException e) {
            throw new IllegalStateException("Nao foi possivel abrir o banco: " + e.getMessage(), e);
        }
    }

    public Connection getConexao() { return conexao; }

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
            versao = rs.getInt(1);
        }
        if (versao >= VERSAO_ESQUEMA) return;

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
            st.execute("PRAGMA user_version = " + VERSAO_ESQUEMA);
            conexao.commit();
        } catch (SQLException e) {
            conexao.rollback();
            throw e;
        } finally {
            conexao.setAutoCommit(true);
        }
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
