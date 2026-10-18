package br.com.kleber.psigestor.dao;

import br.com.kleber.psigestor.model.Convenio;
import br.com.kleber.psigestor.util.RegraNegocioException;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class ConvenioDAO {
    private final Connection con;

    public ConvenioDAO(Connection con) { this.con = con; }

    public Convenio salvar(Convenio c) {
        boolean novo = c.getId() == null;
        String sql = novo
            ? "INSERT INTO convenio (nome, registro_ans, valor_sessao, prazo_pagamento_dias) VALUES (?,?,?,?)"
            : "UPDATE convenio SET nome=?, registro_ans=?, valor_sessao=?, prazo_pagamento_dias=? WHERE id=?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, c.getNome());
            ps.setString(2, c.getRegistroAns());
            ps.setLong(3, c.getValorSessaoCentavos());
            ps.setInt(4, c.getPrazoPagamentoDias());
            if (!novo) ps.setInt(5, c.getId());
            if (ps.executeUpdate() != 1) throw new RegraNegocioException("Convenio nao encontrado.");
            if (novo) c.setId(JdbcUtil.ultimoId(ps));
            return c;
        } catch (SQLException e) { throw new DaoException("Erro ao salvar convenio", e); }
    }

    public List<Convenio> listar() {
        List<Convenio> lista = new ArrayList<>();
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM convenio ORDER BY nome")) {
            while (rs.next()) {
                Convenio c = new Convenio(rs.getString("nome"), rs.getString("registro_ans"),
                        rs.getLong("valor_sessao"), rs.getInt("prazo_pagamento_dias"));
                c.setId(rs.getInt("id"));
                lista.add(c);
            }
        } catch (SQLException e) { throw new DaoException("Erro ao listar convenios", e); }
        return lista;
    }
}
