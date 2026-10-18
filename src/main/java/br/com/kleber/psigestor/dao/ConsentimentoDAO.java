package br.com.kleber.psigestor.dao;

import br.com.kleber.psigestor.model.Consentimento;
import br.com.kleber.psigestor.model.TipoConsentimento;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Historico de consentimentos: nunca sobrescreve, sempre insere (vale o mais recente). */
public class ConsentimentoDAO {
    private static final DateTimeFormatter SQLITE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final Connection con;

    public ConsentimentoDAO(Connection con) { this.con = con; }

    public void registrar(int pacienteId, TipoConsentimento tipo, boolean concedido, String obs) {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO consentimento (paciente_id, tipo, concedido, observacao) VALUES (?,?,?,?)")) {
            ps.setInt(1, pacienteId);
            ps.setString(2, tipo.name());
            ps.setInt(3, concedido ? 1 : 0);
            ps.setString(4, obs);
            ps.executeUpdate();
        } catch (SQLException e) { throw new DaoException("Erro ao registrar consentimento", e); }
    }

    public List<Consentimento> historico(int pacienteId) {
        List<Consentimento> lista = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT * FROM consentimento WHERE paciente_id = ? ORDER BY id DESC")) {
            ps.setInt(1, pacienteId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lista.add(new Consentimento(rs.getInt("id"), pacienteId,
                            TipoConsentimento.valueOf(rs.getString("tipo")), rs.getInt("concedido") == 1,
                            LocalDateTime.parse(rs.getString("registrado_em"), SQLITE), rs.getString("observacao")));
                }
            }
        } catch (SQLException e) { throw new DaoException("Erro ao ler consentimentos", e); }
        return lista;
    }

    /** Situacao vigente = ultimo registro daquele tipo. Sem registro = nao concedido. */
    public boolean vigente(int pacienteId, TipoConsentimento tipo) {
        return historico(pacienteId).stream()
                .filter(c -> c.tipo() == tipo)
                .findFirst()
                .map(Consentimento::concedido)
                .orElse(false);
    }
}
