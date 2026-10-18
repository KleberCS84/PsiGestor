package br.com.kleber.psigestor.dao;

import br.com.kleber.psigestor.model.LogAcesso;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class LogAcessoDAO {
    private final Connection con;

    public LogAcessoDAO(Connection con) { this.con = con; }

    public void registrar(Integer usuarioId, String acao, String entidade, Integer entidadeId) {
        registrar(usuarioId, acao, entidade, entidadeId, null);
    }

    public void registrar(Integer usuarioId, String acao, String entidade, Integer entidadeId, String detalhe) {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO log_acesso (usuario_id, acao, entidade, entidade_id, detalhe) VALUES (?,?,?,?,?)")) {
            JdbcUtil.setIntOuNull(ps, 1, usuarioId);
            ps.setString(2, acao);
            ps.setString(3, entidade);
            JdbcUtil.setIntOuNull(ps, 4, entidadeId);
            ps.setString(5, detalhe);
            ps.executeUpdate();
        } catch (SQLException e) { throw new DaoException("Erro ao gravar log", e); }
    }

    public List<LogAcesso> ultimos(int limite) {
        String sql = """
            SELECT l.id, COALESCE(u.nome, '-') AS usuario, l.acao, l.entidade, l.entidade_id, l.data_hora, l.detalhe
              FROM log_acesso l LEFT JOIN usuario u ON u.id = l.usuario_id
             ORDER BY l.id DESC LIMIT ?""";
        List<LogAcesso> lista = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, limite);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lista.add(new LogAcesso(rs.getInt("id"), rs.getString("usuario"), rs.getString("acao"),
                            rs.getString("entidade"), JdbcUtil.getIntOuNull(rs, "entidade_id"),
                            rs.getString("data_hora"), rs.getString("detalhe")));
                }
            }
        } catch (SQLException e) { throw new DaoException("Erro ao ler log", e); }
        return lista;
    }
}
