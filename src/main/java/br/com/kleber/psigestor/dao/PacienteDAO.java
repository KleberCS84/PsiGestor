package br.com.kleber.psigestor.dao;

import br.com.kleber.psigestor.model.Paciente;
import br.com.kleber.psigestor.util.RegraNegocioException;

import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class PacienteDAO {
    private final Connection con;

    public PacienteDAO(Connection con) { this.con = con; }

    public Paciente salvar(Paciente p) {
        boolean novo = p.getId() == null;
        String sql = novo
            ? """
              INSERT INTO paciente (nome, cpf, data_nascimento, telefone, email, contato_emergencia,
                                    responsavel_legal, convenio_id, numero_carteirinha, arquivado)
              VALUES (?,?,?,?,?,?,?,?,?,?)"""
            : """
              UPDATE paciente SET nome=?, cpf=?, data_nascimento=?, telefone=?, email=?,
                     contato_emergencia=?, responsavel_legal=?, convenio_id=?, numero_carteirinha=?,
                     arquivado=? WHERE id=?""";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, p.getNome());
            ps.setString(2, p.getCpf());
            ps.setString(3, p.getDataNascimento() == null ? null : p.getDataNascimento().toString());
            ps.setString(4, p.getTelefone());
            ps.setString(5, p.getEmail());
            ps.setString(6, p.getContatoEmergencia());
            ps.setString(7, p.getResponsavelLegal());
            JdbcUtil.setIntOuNull(ps, 8, p.getConvenioId());
            ps.setString(9, p.getNumeroCarteirinha());
            ps.setInt(10, p.isArquivado() ? 1 : 0);
            if (!novo) ps.setInt(11, p.getId());
            if (ps.executeUpdate() != 1) throw new RegraNegocioException("Paciente nao encontrado.");
            if (novo) p.setId(JdbcUtil.ultimoId(ps));
            return p;
        } catch (SQLException e) { throw new DaoException("Erro ao salvar paciente", e); }
    }

    public Optional<Paciente> buscar(int id) {
        try (PreparedStatement ps = con.prepareStatement("SELECT * FROM paciente WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapear(rs)) : Optional.empty();
            }
        } catch (SQLException e) { throw new DaoException("Erro ao buscar paciente", e); }
    }

    public boolean cpfEmUso(String cpf, Integer ignorarId) {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT COUNT(*) FROM paciente WHERE cpf = ? AND id <> ?")) {
            ps.setString(1, cpf);
            ps.setInt(2, ignorarId == null ? -1 : ignorarId);
            try (ResultSet rs = ps.executeQuery()) { return rs.getInt(1) > 0; }
        } catch (SQLException e) { throw new DaoException("Erro ao verificar CPF", e); }
    }

    /** Busca por parte do nome (LIKE), incluindo ou nao os arquivados. */
    public List<Paciente> pesquisar(String trechoNome, boolean incluirArquivados) {
        String sql = "SELECT * FROM paciente WHERE nome LIKE ? "
                + (incluirArquivados ? "" : "AND arquivado = 0 ") + "ORDER BY nome";
        List<Paciente> lista = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, "%" + (trechoNome == null ? "" : trechoNome.trim()) + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) lista.add(mapear(rs));
            }
        } catch (SQLException e) { throw new DaoException("Erro ao pesquisar pacientes", e); }
        return lista;
    }

    private Paciente mapear(ResultSet rs) throws SQLException {
        Paciente p = new Paciente();
        p.setId(rs.getInt("id"));
        p.setNome(rs.getString("nome"));
        p.setCpf(rs.getString("cpf"));
        String dn = rs.getString("data_nascimento");
        p.setDataNascimento(dn == null ? null : LocalDate.parse(dn));
        p.setTelefone(rs.getString("telefone"));
        p.setEmail(rs.getString("email"));
        p.setContatoEmergencia(rs.getString("contato_emergencia"));
        p.setResponsavelLegal(rs.getString("responsavel_legal"));
        p.setConvenioId(JdbcUtil.getIntOuNull(rs, "convenio_id"));
        p.setNumeroCarteirinha(rs.getString("numero_carteirinha"));
        p.setArquivado(rs.getInt("arquivado") == 1);
        return p;
    }
}
