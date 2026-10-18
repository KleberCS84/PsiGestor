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
            try (ResultSet rs = ps.executeQuery()) { return rs.next() && rs.getInt(1) > 0; }
        } catch (SQLException e) { throw new DaoException("Erro ao verificar CPF", e); }
    }

    /**
     * Data do ultimo atendimento do paciente (qualquer status) ou, se nunca houve, do cadastro.
     * atendimento.inicio ("yyyy-MM-ddTHH:mm") e criado_em ("yyyy-MM-dd HH:mm:ss") comecam pela data ISO.
     */
    public LocalDate ultimaReferencia(int id) {
        String sql = """
            SELECT COALESCE((SELECT MAX(inicio) FROM atendimento WHERE paciente_id = p.id), p.criado_em)
              FROM paciente p WHERE p.id = ?""";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new RegraNegocioException("Paciente nao encontrado.");
                return LocalDate.parse(rs.getString(1).substring(0, 10));
            }
        } catch (SQLException e) { throw new DaoException("Erro ao consultar ultimo atendimento", e); }
    }

    /**
     * Substitui os dados identificadores por valores neutros. Id, atendimentos, financeiro e
     * prontuario continuam ligados ao mesmo id. CPF vira NULL (coluna UNIQUE).
     */
    public void anonimizar(int id) {
        String sql = """
            UPDATE paciente SET nome = 'Paciente anonimizado #' || id, cpf = NULL, data_nascimento = NULL,
                   telefone = NULL, email = NULL, contato_emergencia = NULL
             WHERE id = ?""";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, id);
            if (ps.executeUpdate() != 1) throw new RegraNegocioException("Paciente nao encontrado.");
        } catch (SQLException e) { throw new DaoException("Erro ao anonimizar paciente", e); }
    }

    /** Busca por parte do nome (LIKE), incluindo ou nao os arquivados. */
    public List<Paciente> pesquisar(String trechoNome, boolean incluirArquivados) {
        String sql = "SELECT * FROM paciente WHERE nome LIKE ? ESCAPE '\\' "
                + (incluirArquivados ? "" : "AND arquivado = 0 ") + "ORDER BY nome";
        List<Paciente> lista = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, "%" + escaparLike(trechoNome == null ? "" : trechoNome.trim()) + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) lista.add(mapear(rs));
            }
        } catch (SQLException e) { throw new DaoException("Erro ao pesquisar pacientes", e); }
        return lista;
    }

    /** % e _ digitados sao texto, nao curinga. A barra vai primeiro para nao escapar o escape. */
    static String escaparLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
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
