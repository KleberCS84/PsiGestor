package br.com.kleber.psigestor.dao;

import br.com.kleber.psigestor.model.Servico;
import br.com.kleber.psigestor.util.RegraNegocioException;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ServicoDAO {
    private final Connection con;

    public ServicoDAO(Connection con) { this.con = con; }

    public Servico salvar(Servico s) {
        boolean novo = s.getId() == null;
        String sql = novo
            ? "INSERT INTO servico (nome, codigo_tuss, duracao_min, valor) VALUES (?,?,?,?)"
            : "UPDATE servico SET nome=?, codigo_tuss=?, duracao_min=?, valor=? WHERE id=?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, s.getNome());
            ps.setString(2, s.getCodigoTuss());
            ps.setInt(3, s.getDuracaoMin());
            ps.setDouble(4, s.getValor());
            if (!novo) ps.setInt(5, s.getId());
            if (ps.executeUpdate() != 1) throw new RegraNegocioException("Servico nao encontrado.");
            if (novo) s.setId(JdbcUtil.ultimoId(ps));
            return s;
        } catch (SQLException e) { throw new DaoException("Erro ao salvar servico", e); }
    }

    public Optional<Servico> buscar(int id) {
        return listar().stream().filter(s -> s.getId() == id).findFirst();
    }

    public List<Servico> listar() {
        List<Servico> lista = new ArrayList<>();
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM servico ORDER BY nome")) {
            while (rs.next()) {
                Servico s = new Servico(rs.getString("nome"), rs.getString("codigo_tuss"),
                        rs.getInt("duracao_min"), rs.getDouble("valor"));
                s.setId(rs.getInt("id"));
                lista.add(s);
            }
        } catch (SQLException e) { throw new DaoException("Erro ao listar servicos", e); }
        return lista;
    }
}
