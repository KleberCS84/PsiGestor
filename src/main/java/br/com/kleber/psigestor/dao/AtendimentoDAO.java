package br.com.kleber.psigestor.dao;

import br.com.kleber.psigestor.model.Atendimento;
import br.com.kleber.psigestor.model.Modalidade;
import br.com.kleber.psigestor.model.StatusAtendimento;
import br.com.kleber.psigestor.util.RegraNegocioException;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class AtendimentoDAO {
    /** Formato fixo: permite comparar datas como texto no SQLite ("2026-10-11T14:00"). */
    public static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private static final String SELECT_BASE = """
        SELECT a.*, p.nome AS paciente_nome, u.nome AS profissional_nome, s.nome AS servico_nome
          FROM atendimento a
          JOIN paciente p ON p.id = a.paciente_id
          JOIN usuario  u ON u.id = a.profissional_id
          JOIN servico  s ON s.id = a.servico_id
        """;

    private final Connection con;

    public AtendimentoDAO(Connection con) { this.con = con; }

    public Atendimento inserir(Atendimento a) {
        String sql = """
            INSERT INTO atendimento (paciente_id, profissional_id, servico_id, convenio_id, inicio, fim,
                                     status, modalidade, valor, pago, forma_pagamento, recibo_emitido, observacao)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, a.getPacienteId());
            ps.setInt(2, a.getProfissionalId());
            ps.setInt(3, a.getServicoId());
            JdbcUtil.setIntOuNull(ps, 4, a.getConvenioId());
            ps.setString(5, a.getInicio().format(FMT));
            ps.setString(6, a.getFim().format(FMT));
            ps.setString(7, a.getStatus().name());
            ps.setString(8, a.getModalidade().name());
            ps.setLong(9, a.getValorCentavos());
            ps.setInt(10, a.isPago() ? 1 : 0);
            ps.setString(11, a.getFormaPagamento());
            ps.setInt(12, a.isReciboEmitido() ? 1 : 0);
            ps.setString(13, a.getObservacao());
            ps.executeUpdate();
            a.setId(JdbcUtil.ultimoId(ps));
            return a;
        } catch (SQLException e) { throw new DaoException("Erro ao agendar", e); }
    }

    /**
     * Grava a edicao de um agendamento (paciente, profissional, status e pagamento nao mudam).
     * O filtro de status repete a regra do service: entre a leitura e o UPDATE nada vira REALIZADO.
     */
    public void atualizarAgendamento(Atendimento a) {
        executar("""
                UPDATE atendimento SET servico_id = ?, inicio = ?, fim = ?, modalidade = ?, valor = ?, observacao = ?
                 WHERE id = ? AND status IN ('AGENDADO','CONFIRMADO')""",
                a.getServicoId(), a.getInicio().format(FMT), a.getFim().format(FMT), a.getModalidade().name(),
                a.getValorCentavos(), a.getObservacao(), a.getId());
    }

    public void atualizarStatus(int id, StatusAtendimento status) {
        executar("UPDATE atendimento SET status = ? WHERE id = ?", status.name(), id);
    }

    /** Muda o status e congela o % de repasse da clinica vigente agora (sessao realizada/falta). */
    public void atualizarStatus(int id, StatusAtendimento status, double percentualRepasseClinica) {
        executar("UPDATE atendimento SET status = ?, percentual_repasse_clinica = ? WHERE id = ?",
                status.name(), percentualRepasseClinica, id);
    }

    public void registrarPagamento(int id, String formaPagamento) {
        executar("UPDATE atendimento SET pago = 1, forma_pagamento = ? WHERE id = ?", formaPagamento, id);
    }

    public void marcarReciboEmitido(int id) {
        executar("UPDATE atendimento SET recibo_emitido = 1 WHERE id = ?", id);
    }

    public Optional<Atendimento> buscar(int id) {
        try (PreparedStatement ps = con.prepareStatement(SELECT_BASE + " WHERE a.id = ?")) {
            ps.setInt(1, id);
            return listar(ps).stream().findFirst();
        } catch (SQLException e) { throw new DaoException("Erro ao buscar atendimento", e); }
    }

    /** Atendimentos do profissional que se sobrepoem ao intervalo (ignorando cancelados). */
    public int contarConflitos(int profissionalId, LocalDateTime inicio, LocalDateTime fim, Integer ignorarId) {
        return contarSobrepostos("profissional_id", profissionalId, inicio, fim, ignorarId);
    }

    /** Idem, para o mesmo paciente (nao pode estar em duas sessoes ao mesmo tempo). */
    public int contarConflitosPaciente(int pacienteId, LocalDateTime inicio, LocalDateTime fim, Integer ignorarId) {
        return contarSobrepostos("paciente_id", pacienteId, inicio, fim, ignorarId);
    }

    /** coluna vem sempre de constante interna, nunca do usuario. */
    private int contarSobrepostos(String coluna, int valor, LocalDateTime inicio, LocalDateTime fim, Integer ignorarId) {
        String sql = "SELECT COUNT(*) FROM atendimento WHERE " + coluna + " = ? AND status <> 'CANCELADO'"
                + " AND inicio < ? AND fim > ? AND id <> ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, valor);
            ps.setString(2, fim.format(FMT));
            ps.setString(3, inicio.format(FMT));
            ps.setInt(4, ignorarId == null ? -1 : ignorarId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        } catch (SQLException e) { throw new DaoException("Erro ao verificar agenda", e); }
    }

    /** Lista do intervalo [de, ate). profissionalId null = todos. */
    public List<Atendimento> listarPeriodo(LocalDateTime de, LocalDateTime ate, Integer profissionalId) {
        String sql = SELECT_BASE + " WHERE a.inicio >= ? AND a.inicio < ? "
                + (profissionalId == null ? "" : " AND a.profissional_id = ? ") + " ORDER BY a.inicio";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, de.format(FMT));
            ps.setString(2, ate.format(FMT));
            if (profissionalId != null) ps.setInt(3, profissionalId);
            return listar(ps);
        } catch (SQLException e) { throw new DaoException("Erro ao listar agenda", e); }
    }

    /**
     * Consulta de agenda: intervalo [de, ate), paciente opcional (null = todos) e status do conjunto
     * (nao vazio). Ordem por data e hora; so parametros "?" (o IN ganha um "?" por status).
     */
    public List<Atendimento> consultar(Integer pacienteId, LocalDateTime de, LocalDateTime ate,
                                       java.util.Set<StatusAtendimento> status) {
        String sql = SELECT_BASE + " WHERE a.inicio >= ? AND a.inicio < ? "
                + (pacienteId == null ? "" : " AND a.paciente_id = ? ")
                + " AND a.status IN (" + String.join(",", java.util.Collections.nCopies(status.size(), "?")) + ")"
                + " ORDER BY a.inicio";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, de.format(FMT));
            ps.setString(i++, ate.format(FMT));
            if (pacienteId != null) ps.setInt(i++, pacienteId);
            for (StatusAtendimento s : status) ps.setString(i++, s.name());
            return listar(ps);
        } catch (SQLException e) { throw new DaoException("Erro ao consultar agenda", e); }
    }

    public List<Atendimento> listarPorPaciente(int pacienteId) {
        try (PreparedStatement ps = con.prepareStatement(SELECT_BASE + " WHERE a.paciente_id = ? ORDER BY a.inicio DESC")) {
            ps.setInt(1, pacienteId);
            return listar(ps);
        } catch (SQLException e) { throw new DaoException("Erro ao listar atendimentos", e); }
    }

    private List<Atendimento> listar(PreparedStatement ps) throws SQLException {
        List<Atendimento> lista = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Atendimento a = new Atendimento();
                a.setId(rs.getInt("id"));
                a.setPacienteId(rs.getInt("paciente_id"));
                a.setProfissionalId(rs.getInt("profissional_id"));
                a.setServicoId(rs.getInt("servico_id"));
                a.setConvenioId(JdbcUtil.getIntOuNull(rs, "convenio_id"));
                a.setInicio(LocalDateTime.parse(rs.getString("inicio"), FMT));
                a.setFim(LocalDateTime.parse(rs.getString("fim"), FMT));
                a.setStatus(StatusAtendimento.valueOf(rs.getString("status")));
                a.setModalidade(Modalidade.valueOf(rs.getString("modalidade")));
                a.setValorCentavos(rs.getLong("valor"));
                a.setPago(rs.getInt("pago") == 1);
                a.setFormaPagamento(rs.getString("forma_pagamento"));
                a.setReciboEmitido(rs.getInt("recibo_emitido") == 1);
                double perc = rs.getDouble("percentual_repasse_clinica");
                a.setPercentualRepasseClinica(rs.wasNull() ? null : perc);
                a.setObservacao(rs.getString("observacao"));
                a.setPacienteNome(rs.getString("paciente_nome"));
                a.setProfissionalNome(rs.getString("profissional_nome"));
                a.setServicoNome(rs.getString("servico_nome"));
                lista.add(a);
            }
        }
        return lista;
    }

    private void executar(String sql, Object... params) {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            // id inexistente: sem isso o service auditaria um evento que nao aconteceu
            if (ps.executeUpdate() != 1) throw new RegraNegocioException("Atendimento não encontrado.");
        } catch (SQLException e) { throw new DaoException("Erro ao atualizar atendimento", e); }
    }
}
