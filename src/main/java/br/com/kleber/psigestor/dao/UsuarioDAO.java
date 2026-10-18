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
                                 percentual_repasse_clinica, ativo, chave_cifrada, chave_iv)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)""";
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
            if (ps.executeUpdate() != 1) throw new RegraNegocioException("Usuario nao encontrado.");
        } catch (SQLException e) { throw new DaoException("Erro ao atualizar repasse", e); }
    }

    /** Revoga o acesso sem apagar: o usuario continua referenciado por atendimentos, prontuario e log. */
    public void desativar(int id) {
        try (PreparedStatement ps = con.prepareStatement("UPDATE usuario SET ativo = 0 WHERE id = ?")) {
            ps.setInt(1, id);
            if (ps.executeUpdate() != 1) throw new RegraNegocioException("Usuario nao encontrado.");
        } catch (SQLException e) { throw new DaoException("Erro ao desativar usuario", e); }
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

    /** Grava senha e embrulho da chave juntos: um unico UPDATE e atomico, nao fica "meio trocado". */
    public void atualizarCredenciais(Usuario u) {
        String sql = """
            UPDATE usuario SET senha_hash = ?, senha_salt = ?, chave_salt = ?, chave_cifrada = ?, chave_iv = ?
             WHERE id = ?""";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, u.getSenhaHash());
            ps.setString(2, u.getSenhaSalt());
            ps.setString(3, u.getChaveSalt());
            ps.setBytes(4, u.getChaveCifrada());
            ps.setBytes(5, u.getChaveIv());
            ps.setInt(6, u.getId());
            ps.executeUpdate();
        } catch (SQLException e) { throw new DaoException("Erro ao atualizar credenciais", e); }
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
            return rs.getInt(1);
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
        u.setPapel(Papel.valueOf(rs.getString("papel")));
        u.setCrp(rs.getString("crp"));
        u.setPercentualRepasseClinica(rs.getDouble("percentual_repasse_clinica"));
        u.setAtivo(rs.getInt("ativo") == 1);
        return u;
    }
}
