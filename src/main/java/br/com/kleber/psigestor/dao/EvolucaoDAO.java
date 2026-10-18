package br.com.kleber.psigestor.dao;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/** Acesso aos registros CIFRADOS. Nao existe metodo de update/delete (e o banco tambem bloqueia). */
public class EvolucaoDAO {
    private final Connection con;

    /** Linha bruta do banco: o texto ainda esta cifrado. */
    public record LinhaCifrada(int id, int pacienteId, Integer atendimentoId, int profissionalId,
                               String registradoEm, byte[] conteudo, byte[] iv, Integer retificaId) { }

    public EvolucaoDAO(Connection con) { this.con = con; }

    public int inserir(int pacienteId, Integer atendimentoId, int profissionalId,
                       byte[] conteudo, byte[] iv, Integer retificaId) {
        String sql = """
            INSERT INTO evolucao (paciente_id, atendimento_id, profissional_id, conteudo_cifrado, iv, retifica_id)
            VALUES (?,?,?,?,?,?)""";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, pacienteId);
            JdbcUtil.setIntOuNull(ps, 2, atendimentoId);
            ps.setInt(3, profissionalId);
            ps.setBytes(4, conteudo);
            ps.setBytes(5, iv);
            JdbcUtil.setIntOuNull(ps, 6, retificaId);
            ps.executeUpdate();
            return JdbcUtil.ultimoId(ps);
        } catch (SQLException e) { throw new DaoException("Erro ao gravar registro de prontuario", e); }
    }

    public List<LinhaCifrada> listarPorPaciente(int pacienteId) {
        List<LinhaCifrada> lista = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT * FROM evolucao WHERE paciente_id = ? ORDER BY registrado_em DESC, id DESC")) {
            ps.setInt(1, pacienteId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lista.add(new LinhaCifrada(rs.getInt("id"), rs.getInt("paciente_id"),
                            JdbcUtil.getIntOuNull(rs, "atendimento_id"), rs.getInt("profissional_id"),
                            rs.getString("registrado_em"), rs.getBytes("conteudo_cifrado"), rs.getBytes("iv"),
                            JdbcUtil.getIntOuNull(rs, "retifica_id")));
                }
            }
        } catch (SQLException e) { throw new DaoException("Erro ao ler prontuario", e); }
        return lista;
    }
}
