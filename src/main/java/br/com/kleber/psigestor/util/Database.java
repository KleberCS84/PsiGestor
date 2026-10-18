package br.com.kleber.psigestor.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Mantem UMA conexao JDBC com o arquivo SQLite (app desktop de usuario unico por vez).
 * Use "jdbc:sqlite::memory:" nos testes.
 */
public class Database implements AutoCloseable {

    private final Connection conexao;

    public Database(String url) {
        try {
            conexao = DriverManager.getConnection(url);
            try (Statement st = conexao.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");   // SQLite vem com FK desligada!
            }
            criarEsquema();
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
