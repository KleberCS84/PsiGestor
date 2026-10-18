package br.com.kleber.psigestor.dao;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/** Pequenos utilitarios para lidar com NULL no JDBC. */
final class JdbcUtil {
    private JdbcUtil() { }

    static void setIntOuNull(PreparedStatement ps, int idx, Integer valor) throws SQLException {
        if (valor == null) ps.setNull(idx, Types.INTEGER); else ps.setInt(idx, valor);
    }

    static Integer getIntOuNull(ResultSet rs, String coluna) throws SQLException {
        int v = rs.getInt(coluna);
        return rs.wasNull() ? null : v;
    }

    /** Id gerado pelo ultimo INSERT nesta conexao (funcao nativa do SQLite). */
    static int ultimoId(PreparedStatement ps) throws SQLException {
        try (var st = ps.getConnection().createStatement();
             ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
