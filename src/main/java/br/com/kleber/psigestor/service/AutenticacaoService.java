package br.com.kleber.psigestor.service;

import br.com.kleber.psigestor.model.Papel;
import br.com.kleber.psigestor.model.Usuario;
import br.com.kleber.psigestor.util.Criptografia;
import br.com.kleber.psigestor.util.RegraNegocioException;
import br.com.kleber.psigestor.util.Sessao;
import br.com.kleber.psigestor.util.Validadores;

import javax.crypto.SecretKey;
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
        if (u.isPsicologo()) {
            // Chave do prontuario aleatoria, guardada embrulhada pela senha (criptografia de envelope)
            embrulhar(u, Criptografia.novaChaveDados(), senha);
        }
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
            SecretKey chave = u.isPsicologo() ? abrirChaveProntuario(u, senha) : null;
            Sessao s = new Sessao(u, chave);
            ctx.setSessao(s);
            ctx.auditar("LOGIN", "usuario", u.getId());
            return s;
        } finally {
            if (senha != null) Arrays.fill(senha, '\0'); // apaga a senha da memoria
        }
    }

    /**
     * Troca a senha do usuario logado. Para o psicologo, a chave do prontuario (DEK) NAO muda:
     * so trocamos o "embrulho" dela. Assim nenhum registro de evolucao e recifrado.
     */
    public void trocarSenha(char[] senhaAtual, char[] novaSenha) {
        try {
            Sessao s = ctx.exigirSessao();
            if (novaSenha == null || novaSenha.length < SENHA_MINIMA)
                throw new RegraNegocioException("A nova senha deve ter pelo menos " + SENHA_MINIMA + " caracteres.");
            if (Arrays.equals(senhaAtual, novaSenha))
                throw new RegraNegocioException("A nova senha deve ser diferente da atual.");
            // Relemos do banco para nao confiar em dados antigos guardados na sessao
            Usuario u = ctx.usuarios.buscarPorLogin(s.usuario().getLogin()).orElseThrow();
            if (senhaAtual == null || !Criptografia.senhaConfere(senhaAtual, u.getSenhaSalt(), u.getSenhaHash())) {
                ctx.auditar("TROCA_SENHA_FALHOU", "usuario", u.getId());
                throw new RegraNegocioException("A senha atual nao confere.");
            }
            SecretKey dek = u.isPsicologo() ? abrirChaveProntuario(u, senhaAtual) : null;

            u.setSenhaSalt(Criptografia.novoSalt());
            u.setSenhaHash(Criptografia.hashSenha(novaSenha, u.getSenhaSalt()));
            if (dek != null) embrulhar(u, dek, novaSenha);
            ctx.usuarios.atualizarCredenciais(u);   // um UPDATE so: senha e embrulho mudam juntos
            ctx.setSessao(new Sessao(u, dek));
            ctx.auditar("TROCA_SENHA", "usuario", u.getId());
        } finally {
            if (senhaAtual != null) Arrays.fill(senhaAtual, '\0');
            if (novaSenha != null) Arrays.fill(novaSenha, '\0');
        }
    }

    /**
     * Devolve a chave do prontuario (DEK) a partir da senha ja conferida.
     * Usuario criado antes da v1 nao tem DEK embrulhada: a chave dele era derivada direto da senha.
     * Nesse caso, essa chave antiga PASSA A SER a DEK e e embrulhada agora (migracao no primeiro login),
     * entao os registros antigos continuam legiveis sem recifrar nada.
     */
    private SecretKey abrirChaveProntuario(Usuario u, char[] senha) {
        if (u.getChaveCifrada() != null) {
            SecretKey kek = Criptografia.derivarChave(senha, u.getChaveSalt());
            return Criptografia.desembrulharChave(u.getChaveCifrada(), u.getChaveIv(), kek);
        }
        SecretKey dekAntiga = Criptografia.derivarChave(senha, u.getChaveSalt());
        embrulhar(u, dekAntiga, senha);
        ctx.usuarios.atualizarCredenciais(u);
        ctx.logs.registrar(u.getId(), "MIGRACAO_CHAVE_PRONTUARIO", "usuario", u.getId());
        return dekAntiga;
    }

    /** Gera um salt novo, deriva a KEK da senha e grava a DEK cifrada no objeto (ainda nao no banco). */
    private static void embrulhar(Usuario u, SecretKey dek, char[] senha) {
        u.setChaveSalt(Criptografia.novoSalt());
        SecretKey kek = Criptografia.derivarChave(senha, u.getChaveSalt());
        byte[][] ivECifrada = Criptografia.embrulharChave(dek, kek);
        u.setChaveIv(ivECifrada[0]);
        u.setChaveCifrada(ivECifrada[1]);
    }
}
