package br.com.kleber.psigestor.service;

import br.com.kleber.psigestor.model.Papel;
import br.com.kleber.psigestor.model.Usuario;
import br.com.kleber.psigestor.util.Criptografia;
import br.com.kleber.psigestor.util.RegraNegocioException;
import br.com.kleber.psigestor.util.Sessao;
import br.com.kleber.psigestor.util.Validadores;

import java.util.Arrays;

public class AutenticacaoService {
    public static final int SENHA_MINIMA = 8;
    private final Contexto ctx;

    AutenticacaoService(Contexto ctx) { this.ctx = ctx; }

    public boolean primeiroAcesso() { return ctx.usuarios.contar() == 0; }

    public Usuario cadastrar(String nome, String login, char[] senha, Papel papel, String crp,
                             double percentualRepasseClinica) {
        if (Validadores.vazio(nome) || Validadores.vazio(login))
            throw new RegraNegocioException("Nome e login sao obrigatorios.");
        if (senha == null || senha.length < SENHA_MINIMA)
            throw new RegraNegocioException("A senha deve ter pelo menos " + SENHA_MINIMA + " caracteres.");
        if (papel == Papel.PSICOLOGO && Validadores.vazio(crp))
            throw new RegraNegocioException("Informe o numero do CRP do psicologo.");
        if (ctx.usuarios.buscarPorLogin(login.trim()).isPresent())
            throw new RegraNegocioException("Ja existe um usuario com esse login.");
        // Apenas o primeiro usuario se cadastra sem login; depois, so um psicologo cadastra outros.
        if (!primeiroAcesso()) {
            var s = ctx.exigirSessao();
            if (!s.usuario().isPsicologo())
                throw new RegraNegocioException("Somente psicologos podem cadastrar usuarios.");
        } else if (papel != Papel.PSICOLOGO) {
            throw new RegraNegocioException("O primeiro usuario deve ser o psicologo responsavel.");
        }

        Usuario u = new Usuario(nome.trim(), login.trim(), papel, papel == Papel.PSICOLOGO ? crp.trim() : null);
        u.setSenhaSalt(Criptografia.novoSalt());
        u.setSenhaHash(Criptografia.hashSenha(senha, u.getSenhaSalt()));
        u.setChaveSalt(Criptografia.novoSalt());
        u.setPercentualRepasseClinica(percentualRepasseClinica);
        ctx.usuarios.inserir(u);
        ctx.auditar("CADASTRO_USUARIO", "usuario", u.getId());
        return u;
    }

    public Sessao entrar(String login, char[] senha) {
        try {
            Usuario u = ctx.usuarios.buscarPorLogin(login == null ? "" : login.trim())
                    .filter(Usuario::isAtivo)
                    .orElse(null);
            if (u == null || !Criptografia.senhaConfere(senha, u.getSenhaSalt(), u.getSenhaHash())) {
                ctx.logs.registrar(u == null ? null : u.getId(), "LOGIN_FALHOU", "usuario", null);
                throw new RegraNegocioException("Login ou senha invalidos."); // mensagem generica de proposito
            }
            var chave = u.isPsicologo() ? Criptografia.derivarChave(senha, u.getChaveSalt()) : null;
            Sessao s = new Sessao(u, chave);
            ctx.setSessao(s);
            ctx.auditar("LOGIN", "usuario", u.getId());
            return s;
        } finally {
            if (senha != null) Arrays.fill(senha, '\0'); // apaga a senha da memoria
        }
    }
}
