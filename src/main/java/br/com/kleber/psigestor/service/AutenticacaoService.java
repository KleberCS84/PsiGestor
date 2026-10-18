package br.com.kleber.psigestor.service;

import br.com.kleber.psigestor.model.LogAcesso;
import br.com.kleber.psigestor.model.Papel;
import br.com.kleber.psigestor.model.Usuario;
import br.com.kleber.psigestor.util.Criptografia;
import br.com.kleber.psigestor.util.RegraNegocioException;
import br.com.kleber.psigestor.util.Sessao;
import br.com.kleber.psigestor.util.Validadores;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class AutenticacaoService {
    /** Vale so para senha nova ou trocada: quem ja existe continua entrando com a senha atual. */
    public static final int SENHA_MINIMA = 12;
    /** A partir desta falha seguida o login fica bloqueado; o bloqueio dobra a cada nova falha. */
    static final int FALHAS_ATE_BLOQUEIO = 5;
    static final Duration BLOQUEIO_INICIAL = Duration.ofSeconds(30);
    static final Duration BLOQUEIO_MAXIMO = Duration.ofMinutes(15);
    /** Tamanho maximo de log_acesso.detalhe nas falhas de login. */
    static final int DETALHE_MAXIMO = 40;
    /** Salt descartavel para o PBKDF2 de login inexistente/inativo (B5). Nunca gravado. */
    private static final String SALT_FICTICIO = Criptografia.novoSalt();

    private final Contexto ctx;
    /** Por login digitado (existente ou nao). Em memoria: reabrir o app zera, aceitavel em desktop. */
    private final Map<String, Tentativas> tentativas = new HashMap<>();

    private static final class Tentativas {
        int falhas;
        Instant bloqueadoAte;
    }

    AutenticacaoService(Contexto ctx) { this.ctx = ctx; }

    public boolean primeiroAcesso() { return ctx.usuarios.contar() == 0; }

    public Usuario cadastrar(String nome, String login, char[] senha, Papel papel, String crp,
                             double percentualRepasseClinica) {
        // Apenas o primeiro usuario se cadastra sem login; depois, so um psicologo cadastra outros.
        // Vem antes de tudo: sem permissao, nem "login ja existe" pode ser respondido (enumeracao).
        if (!primeiroAcesso()) {
            var s = ctx.exigirSessao();
            if (!s.usuario().isPsicologo())
                throw new RegraNegocioException("Somente psicologos podem cadastrar usuarios.");
        } else if (papel != Papel.PSICOLOGO) {
            throw new RegraNegocioException("O primeiro usuario deve ser o psicologo responsavel.");
        }
        if (Validadores.vazio(nome) || Validadores.vazio(login))
            throw new RegraNegocioException("Nome e login sao obrigatorios.");
        if (senha == null || senha.length < SENHA_MINIMA)
            throw new RegraNegocioException("A senha deve ter pelo menos " + SENHA_MINIMA + " caracteres.");
        if (papel == Papel.PSICOLOGO && Validadores.vazio(crp))
            throw new RegraNegocioException("Informe o numero do CRP do psicologo.");
        CadastroService.validarPercentual(percentualRepasseClinica);
        if (ctx.usuarios.buscarPorLogin(login.trim()).isPresent())
            throw new RegraNegocioException("Ja existe um usuario com esse login.");

        Usuario u = new Usuario(nome.trim(), login.trim(), papel, papel == Papel.PSICOLOGO ? crp.trim() : null);
        u.setIteracoes(Criptografia.ITERACOES_ATUAL);
        u.setSenhaSalt(Criptografia.novoSalt());
        u.setSenhaHash(Criptografia.hashSenha(senha, u.getSenhaSalt(), u.getIteracoes()));
        u.setChaveSalt(Criptografia.novoSalt());
        if (u.isPsicologo()) {
            // Chave do prontuario aleatoria, guardada embrulhada pela senha (criptografia de envelope)
            embrulhar(u, Criptografia.novaChaveDados(), senha);
        }
        u.setPercentualRepasseClinica(percentualRepasseClinica);
        ctx.transacao(() -> {
            ctx.usuarios.inserir(u);
            ctx.auditar("CADASTRO_USUARIO", "usuario", u.getId());
        });
        return u;
    }

    public Sessao entrar(String login, char[] senha) {
        try {
            String loginLimpo = login == null ? "" : login.trim();
            String chaveTentativa = loginLimpo.toLowerCase(Locale.ROOT);
            Usuario cadastrado = ctx.usuarios.buscarPorLogin(loginLimpo).orElse(null);
            // Texto de login inexistente pode ser a senha digitada no campo errado: nao vai para o log
            String detalhe = cadastrado == null ? "login inexistente" : limitar("login=" + cadastrado.getLogin());
            Usuario u = cadastrado != null && cadastrado.isAtivo() ? cadastrado : null;
            Integer uid = u == null ? null : u.getId();

            // Bloqueado: recusa ate a senha certa, senao o bloqueio nao impede adivinhar
            Tentativas t = tentativas.get(chaveTentativa);
            Instant agora = ctx.agora();
            if (t != null && t.bloqueadoAte != null && agora.isBefore(t.bloqueadoAte)) {
                ctx.logs.registrar(uid, "LOGIN_BLOQUEADO", "usuario", null, detalhe);
                long seg = Math.max(1, Duration.between(agora, t.bloqueadoAte).toSeconds());
                throw new RegraNegocioException("Muitas tentativas. Tente novamente em " + seg + " s.");
            }
            if (u == null) {
                // mesmo custo de uma senha errada: o tempo de resposta nao revela se o login existe
                Criptografia.hashSenha(senha == null ? new char[0] : senha, SALT_FICTICIO, Criptografia.ITERACOES_ATUAL);
            }
            if (u == null || !Criptografia.senhaConfere(senha, u.getSenhaSalt(), u.getSenhaHash(), u.getIteracoes())) {
                registrarFalha(chaveTentativa, agora);
                ctx.logs.registrar(uid, "LOGIN_FALHOU", "usuario", null, detalhe);
                throw new RegraNegocioException("Login ou senha invalidos."); // mensagem generica de proposito
            }
            tentativas.remove(chaveTentativa);
            try {
                // migracoes (chave antiga, iteracoes) + LOGIN: ou tudo fica no banco, ou nada
                return ctx.transacao(() -> {
                    SecretKey chave = u.isPsicologo() ? abrirChaveProntuario(u, senha) : null;
                    if (u.getIteracoes() < Criptografia.ITERACOES_ATUAL) atualizarIteracoes(u, senha, chave);
                    Sessao s = new Sessao(u, chave);
                    ctx.setSessao(s);
                    ctx.auditar("LOGIN", "usuario", u.getId());
                    return s;
                });
            } catch (RuntimeException e) {
                ctx.setSessao(null);   // login sem trilha nao vale
                throw e;
            }
        } finally {
            if (senha != null) Arrays.fill(senha, '\0'); // apaga a senha da memoria
        }
    }

    private static String limitar(String detalhe) {
        return detalhe.length() > DETALHE_MAXIMO ? detalhe.substring(0, DETALHE_MAXIMO) : detalhe;
    }

    /** 5a falha: 30 s; 6a: 60 s; 7a: 120 s ... ate 15 min. */
    private void registrarFalha(String chaveTentativa, Instant agora) {
        Tentativas t = tentativas.computeIfAbsent(chaveTentativa, k -> new Tentativas());
        t.falhas++;
        if (t.falhas >= FALHAS_ATE_BLOQUEIO) {
            int excesso = Math.min(t.falhas - FALHAS_ATE_BLOQUEIO, 10);   // evita estouro no shift
            Duration d = BLOQUEIO_INICIAL.multipliedBy(1L << excesso);
            t.bloqueadoAte = agora.plus(d.compareTo(BLOQUEIO_MAXIMO) > 0 ? BLOQUEIO_MAXIMO : d);
        }
    }

    /**
     * Revoga o acesso de quem saiu da clinica (LGPD art. 46). Nao apaga: o usuario e referenciado
     * por atendimentos, prontuario e log. A clinica nunca pode ficar sem psicologo ativo.
     */
    public void desativar(int usuarioId) {
        Sessao s = ctx.exigirSessao();
        if (!s.usuario().isPsicologo())
            throw new RegraNegocioException("Somente psicologos podem desativar usuarios.");
        if (s.usuario().getId() == usuarioId)
            throw new RegraNegocioException("Nao e possivel desativar a propria conta. Peca a outro psicologo.");
        Usuario alvo = ctx.usuarios.buscar(usuarioId)
                .orElseThrow(() -> new RegraNegocioException("Usuario nao encontrado."));
        if (!alvo.isAtivo()) throw new RegraNegocioException("Usuario ja esta desativado.");
        if (alvo.isPsicologo() && ctx.usuarios.contarPsicologosAtivos() <= 1)
            throw new RegraNegocioException("Nao e possivel desativar o ultimo psicologo ativo.");
        ctx.transacao(() -> {
            ctx.usuarios.desativar(usuarioId);
            ctx.auditar("DESATIVACAO_USUARIO", "usuario", usuarioId);
        });
    }

    /** Ultimos eventos da trilha de auditoria (tela Privacidade). A UI nao le o DAO direto. */
    public List<LogAcesso> trilhaDeAcesso(int limite) {
        ctx.exigirSessao();
        return ctx.logs.ultimos(limite);
    }

    /** Desfaz uma desativacao (ex.: usuario errado, profissional que voltou). So psicologo, auditado. */
    public void reativar(int usuarioId) {
        Sessao s = ctx.exigirSessao();
        if (!s.usuario().isPsicologo())
            throw new RegraNegocioException("Somente psicologos podem reativar usuarios.");
        Usuario alvo = ctx.usuarios.buscar(usuarioId)
                .orElseThrow(() -> new RegraNegocioException("Usuario nao encontrado."));
        if (alvo.isAtivo()) throw new RegraNegocioException("Usuario ja esta ativo.");
        ctx.transacao(() -> {
            ctx.usuarios.reativar(usuarioId);
            ctx.auditar("REATIVACAO_USUARIO", "usuario", usuarioId);
        });
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
            if (senhaAtual == null
                    || !Criptografia.senhaConfere(senhaAtual, u.getSenhaSalt(), u.getSenhaHash(), u.getIteracoes())) {
                ctx.auditar("TROCA_SENHA_FALHOU", "usuario", u.getId());
                throw new RegraNegocioException("A senha atual nao confere.");
            }
            SecretKey dek = u.isPsicologo() ? abrirChaveProntuario(u, senhaAtual) : null;

            u.setIteracoes(Criptografia.ITERACOES_ATUAL);   // a partir daqui, hash e KEK novos
            u.setSenhaSalt(Criptografia.novoSalt());
            u.setSenhaHash(Criptografia.hashSenha(novaSenha, u.getSenhaSalt(), u.getIteracoes()));
            if (dek != null) embrulhar(u, dek, novaSenha);
            ctx.transacao(() -> {
                ctx.usuarios.atualizarCredenciais(u);   // um UPDATE so: senha e embrulho mudam juntos
                ctx.auditar("TROCA_SENHA", "usuario", u.getId());
            });
            ctx.setSessao(new Sessao(u, dek));
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
            SecretKey kek = Criptografia.derivarChave(senha, u.getChaveSalt(), u.getIteracoes());
            return Criptografia.desembrulharChave(u.getChaveCifrada(), u.getChaveIv(), kek);
        }
        SecretKey dekAntiga = Criptografia.derivarChave(senha, u.getChaveSalt(), u.getIteracoes());
        embrulhar(u, dekAntiga, senha);
        ctx.usuarios.atualizarCredenciais(u);
        ctx.logs.registrar(u.getId(), "MIGRACAO_CHAVE_PRONTUARIO", "usuario", u.getId());
        return dekAntiga;
    }

    /**
     * Usuario com menos iteracoes que o atual (senha ja conferida): rederiva o hash da senha e
     * embrulha a MESMA DEK com uma KEK nova. Os registros do prontuario nao sao recifrados.
     * Roda dentro da transacao do login.
     */
    private void atualizarIteracoes(Usuario u, char[] senha, SecretKey dek) {
        u.setIteracoes(Criptografia.ITERACOES_ATUAL);
        u.setSenhaSalt(Criptografia.novoSalt());
        u.setSenhaHash(Criptografia.hashSenha(senha, u.getSenhaSalt(), u.getIteracoes()));
        if (dek != null) embrulhar(u, dek, senha);
        ctx.usuarios.atualizarCredenciais(u);
        ctx.logs.registrar(u.getId(), "MIGRACAO_ITERACOES_SENHA", "usuario", u.getId());
    }

    /** Gera um salt novo, deriva a KEK da senha e grava a DEK cifrada no objeto (ainda nao no banco). */
    private static void embrulhar(Usuario u, SecretKey dek, char[] senha) {
        u.setChaveSalt(Criptografia.novoSalt());
        SecretKey kek = Criptografia.derivarChave(senha, u.getChaveSalt(), u.getIteracoes());
        byte[][] ivECifrada = Criptografia.embrulharChave(dek, kek);
        u.setChaveIv(ivECifrada[0]);
        u.setChaveCifrada(ivECifrada[1]);
    }
}
