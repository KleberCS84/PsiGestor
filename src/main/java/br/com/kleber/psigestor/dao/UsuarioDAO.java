package br.com.kleber.psigestor.dao;

import br.com.kleber.psigestor.model.Papel;
import br.com.kleber.psigestor.model.Usuario;
import br.com.kleber.psigestor.util.RegraNegocioException;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class UsuarioDAO {
    private final Connection con;

    public UsuarioDAO(Connection con) { this.con = con; }

    public Usuario inserir(Usuario u) {
        String sql = """
            INSERT INTO usuario (nome, login, senha_hash, senha_salt, chave_salt, papel, crp,
                                 percentual_repasse_clinica, ativo, chave_cifrada, chave_iv, iteracoes,
                                 dek_recuperacao, salt_recuperacao, iteracoes_recuperacao)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, u.getNome());
            ps.setString(2, u.getLogin());
            ps.setString(3, u.getSenhaHash());
            ps.setString(4, u.getSenhaSalt());
            ps.setString(5, u.getChaveSalt());
            ps.setString(6, u.getPapel().name());
            ps.setString(7, u.getCrp());
            ps.setDouble(8, u.getPercentualRepasseClinica());
            ps.setInt(9, u.isAtivo() ? 1 : 0);
            ps.setBytes(10, u.getChaveCifrada());
            ps.setBytes(11, u.getChaveIv());
            ps.setInt(12, u.getIteracoes());
            ps.setBytes(13, u.getDekRecuperacao());
            ps.setString(14, u.getSaltRecuperacao());
            ps.setObject(15, u.getIteracoesRecuperacao());
            ps.executeUpdate();
            u.setId(JdbcUtil.ultimoId(ps));
            return u;
        } catch (SQLException e) { throw new DaoException("Erro ao inserir usuario", e); }
    }

    public void atualizarRepasse(int id, double percentualClinica) {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE usuario SET percentual_repasse_clinica = ? WHERE id = ?")) {
            ps.setDouble(1, percentualClinica);
            ps.setInt(2, id);
            // id inexistente: sem isso o service auditaria um evento que nao aconteceu
            if (ps.executeUpdate() != 1) throw new RegraNegocioException("Usuário não encontrado.");
        } catch (SQLException e) { throw new DaoException("Erro ao atualizar repasse", e); }
    }

    /** Revoga o acesso sem apagar: o usuario continua referenciado por atendimentos, prontuario e log. */
    public void desativar(int id) {
        try (PreparedStatement ps = con.prepareStatement("UPDATE usuario SET ativo = 0 WHERE id = ?")) {
            ps.setInt(1, id);
            if (ps.executeUpdate() != 1) throw new RegraNegocioException("Usuário não encontrado.");
        } catch (SQLException e) { throw new DaoException("Erro ao desativar usuario", e); }
    }

    /** Desfaz desativar: o cadastro (e a chave do prontuario, se psicologo) nunca foi apagado. */
    public void reativar(int id) {
        try (PreparedStatement ps = con.prepareStatement("UPDATE usuario SET ativo = 1 WHERE id = ?")) {
            ps.setInt(1, id);
            if (ps.executeUpdate() != 1) throw new RegraNegocioException("Usuário não encontrado.");
        } catch (SQLException e) { throw new DaoException("Erro ao reativar usuario", e); }
    }

    public Optional<Usuario> buscar(int id) {
        try (PreparedStatement ps = con.prepareStatement("SELECT * FROM usuario WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapear(rs)) : Optional.empty();
            }
        } catch (SQLException e) { throw new DaoException("Erro ao buscar usuario", e); }
    }

    public long contarPsicologosAtivos() {
        return listarPsicologos().size();
    }

    /**
     * Grava senha, embrulho da chave pela senha e embrulho pelo codigo de recuperacao juntos:
     * um unico UPDATE e atomico, nao fica "meio trocado".
     */
    public void atualizarCredenciais(Usuario u) {
        String sql = """
            UPDATE usuario SET senha_hash = ?, senha_salt = ?, chave_salt = ?, chave_cifrada = ?, chave_iv = ?,
                               iteracoes = ?, dek_recuperacao = ?, salt_recuperacao = ?, iteracoes_recuperacao = ?
             WHERE id = ?""";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, u.getSenhaHash());
            ps.setString(2, u.getSenhaSalt());
            ps.setString(3, u.getChaveSalt());
            ps.setBytes(4, u.getChaveCifrada());
            ps.setBytes(5, u.getChaveIv());
            ps.setInt(6, u.getIteracoes());
            ps.setBytes(7, u.getDekRecuperacao());
            ps.setString(8, u.getSaltRecuperacao());
            ps.setObject(9, u.getIteracoesRecuperacao());
            ps.setInt(10, u.getId());
            // id inexistente: sem isso o service auditaria uma troca de senha que nao aconteceu
            if (ps.executeUpdate() != 1) throw new RegraNegocioException("Usuário não encontrado.");
        } catch (SQLException e) { throw new DaoException("Erro ao atualizar credenciais", e); }
    }

    /** Aceite dos termos de uso: versao aceita e quando (texto ISO, hora local). */
    public void registrarAceiteTermos(int id, int versao, String aceitoEm) {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE usuario SET termos_versao = ?, termos_aceitos_em = ? WHERE id = ?")) {
            ps.setInt(1, versao);
            ps.setString(2, aceitoEm);
            ps.setInt(3, id);
            if (ps.executeUpdate() != 1) throw new RegraNegocioException("Usuário não encontrado.");
        } catch (SQLException e) { throw new DaoException("Erro ao registrar aceite dos termos", e); }
    }

    /** Preferencia do proprio usuario: minutos sem atividade ate o logout automatico. */
    public void atualizarInatividade(int id, int minutos) {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE usuario SET minutos_inatividade = ? WHERE id = ?")) {
            ps.setInt(1, minutos);
            ps.setInt(2, id);
            if (ps.executeUpdate() != 1) throw new RegraNegocioException("Usuário não encontrado.");
        } catch (SQLException e) { throw new DaoException("Erro ao atualizar preferencia", e); }
    }

    /** Modelo da mensagem de horarios do proprio usuario; null volta ao padrao. */
    public void atualizarModeloMensagem(int id, String modelo) {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE usuario SET modelo_mensagem_agenda = ? WHERE id = ?")) {
            ps.setString(1, modelo);
            ps.setInt(2, id);
            if (ps.executeUpdate() != 1) throw new RegraNegocioException("Usuário não encontrado.");
        } catch (SQLException e) { throw new DaoException("Erro ao atualizar preferencia", e); }
    }

    public Optional<Usuario> buscarPorLogin(String login) {
        try (PreparedStatement ps = con.prepareStatement("SELECT * FROM usuario WHERE login = ?")) {
            ps.setString(1, login);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapear(rs)) : Optional.empty();
            }
        } catch (SQLException e) { throw new DaoException("Erro ao buscar usuario", e); }
    }

    public List<Usuario> listar() {
        List<Usuario> lista = new ArrayList<>();
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM usuario ORDER BY nome")) {
            while (rs.next()) lista.add(mapear(rs));
        } catch (SQLException e) { throw new DaoException("Erro ao listar usuarios", e); }
        return lista;
    }

    public List<Usuario> listarPsicologos() {
        return listar().stream().filter(u -> u.isPsicologo() && u.isAtivo()).toList();
    }

    public int contar() {
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM usuario")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) { throw new DaoException("Erro ao contar usuarios", e); }
    }

    private Usuario mapear(ResultSet rs) throws SQLException {
        Usuario u = new Usuario();
        u.setId(rs.getInt("id"));
        u.setNome(rs.getString("nome"));
        u.setLogin(rs.getString("login"));
        u.setSenhaHash(rs.getString("senha_hash"));
        u.setSenhaSalt(rs.getString("senha_salt"));
        u.setChaveSalt(rs.getString("chave_salt"));
        u.setChaveCifrada(rs.getBytes("chave_cifrada"));
        u.setChaveIv(rs.getBytes("chave_iv"));
        u.setIteracoes(rs.getInt("iteracoes"));
        u.setPapel(Papel.valueOf(rs.getString("papel")));
        u.setCrp(rs.getString("crp"));
        u.setPercentualRepasseClinica(rs.getDouble("percentual_repasse_clinica"));
        u.setAtivo(rs.getInt("ativo") == 1);
        u.setDekRecuperacao(rs.getBytes("dek_recuperacao"));
        u.setSaltRecuperacao(rs.getString("salt_recuperacao"));
        int it = rs.getInt("iteracoes_recuperacao");
        u.setIteracoesRecuperacao(rs.wasNull() ? null : it);
        u.setTermosVersao(rs.getInt("termos_versao"));
        u.setTermosAceitosEm(rs.getString("termos_aceitos_em"));
        u.setMinutosInatividade(rs.getInt("minutos_inatividade"));
        u.setModeloMensagemAgenda(rs.getString("modelo_mensagem_agenda"));
        return u;
    }
}
