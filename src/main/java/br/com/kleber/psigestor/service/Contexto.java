package br.com.kleber.psigestor.service;

import br.com.kleber.psigestor.dao.*;
import br.com.kleber.psigestor.util.Database;
import br.com.kleber.psigestor.util.LogTecnico;
import br.com.kleber.psigestor.util.RegraNegocioException;
import br.com.kleber.psigestor.util.Sessao;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.function.Supplier;

/**
 * "Caixa de ferramentas" da aplicacao: cria os DAOs e services uma unica vez
 * e guarda a sessao do usuario logado (injecao de dependencia feita a mao).
 */
public class Contexto implements AutoCloseable {
    /** Computador esquecido logado expoe agenda e prontuario (sigilo, Codigo de Etica art. 9). */
    public static final Duration TEMPO_INATIVIDADE = Duration.ofMinutes(10);

    private final Database db;
    private Sessao sessao;
    private Clock relogio = Clock.systemDefaultZone();
    private Instant ultimaAtividade;

    public final UsuarioDAO usuarios;
    public final PacienteDAO pacientes;
    public final ConvenioDAO convenios;
    public final ServicoDAO servicos;
    public final AtendimentoDAO atendimentos;
    public final EvolucaoDAO evolucoes;
    public final ConsentimentoDAO consentimentos;
    public final LogAcessoDAO logs;

    public final AutenticacaoService autenticacao;
    public final PacienteService pacienteService;
    public final AgendaService agenda;
    public final ProntuarioService prontuario;
    public final FinanceiroService financeiro;
    public final BackupService backup;
    public final CadastroService cadastros;

    public Contexto(String urlBanco) {
        db = new Database(urlBanco);
        var con = db.getConexao();
        usuarios = new UsuarioDAO(con);
        pacientes = new PacienteDAO(con);
        convenios = new ConvenioDAO(con);
        servicos = new ServicoDAO(con);
        atendimentos = new AtendimentoDAO(con);
        evolucoes = new EvolucaoDAO(con);
        consentimentos = new ConsentimentoDAO(con);
        logs = new LogAcessoDAO(con);

        autenticacao = new AutenticacaoService(this);
        pacienteService = new PacienteService(this);
        agenda = new AgendaService(this);
        prontuario = new ProntuarioService(this);
        financeiro = new FinanceiroService(this);
        backup = new BackupService(this, db);
        cadastros = new CadastroService(this);
    }

    public Sessao getSessao() { return sessao; }

    /** Somente para testes automatizados (verificar o que foi gravado de fato no banco). */
    java.sql.Connection getConexaoParaTeste() { return db.getConexao(); }

    void setSessao(Sessao sessao) {
        this.sessao = sessao;
        ultimaAtividade = agora();
    }

    /** Data de hoje pelo relogio do contexto (fixavel nos testes). */
    LocalDate hoje() { return LocalDate.now(relogio); }

    /** Instante atual pelo relogio do contexto (bloqueio de login e inatividade). */
    Instant agora() { return relogio.instant(); }

    /** Data/hora local pelo relogio do contexto (comparada com log_acesso.data_hora, que e hora local). */
    LocalDateTime agoraLocal() { return LocalDateTime.now(relogio); }

    /** Somente para testes automatizados (datas fixas nao "vencem" com o passar do tempo). */
    void setRelogio(Clock relogio) { this.relogio = relogio; }

    /** Garante que ha alguem logado; devolve a sessao. */
    Sessao exigirSessao() {
        // defesa extra: mesmo que o temporizador da tela nao dispare, sessao parada nao serve
        if (sairSeInativo())
            throw new RegraNegocioException("Sessao expirada por inatividade. Faca login novamente.");
        if (sessao == null) throw new RegraNegocioException("Faca login para continuar.");
        return sessao;
    }

    /** Chamado pela tela a cada tecla/clique: adia o logout automatico. */
    public void registrarAtividade() {
        // sessao ja vencida nao "revive" com um clique antes do temporizador da tela passar
        if (sessao != null && !sairSeInativo()) ultimaAtividade = agora();
    }

    /** Encerra a sessao se passou TEMPO_INATIVIDADE sem atividade; devolve true se encerrou. */
    public boolean sairSeInativo() {
        if (sessao == null || agora().isBefore(ultimaAtividade.plus(TEMPO_INATIVIDADE))) return false;
        encerrarSessao("LOGOUT_INATIVIDADE");
        return true;
    }

    /**
     * Executa operacao + auditoria como uma unidade: ou tudo entra no banco, ou nada entra.
     * Reentrante: chamada dentro de outra transacao so executa (quem abriu faz commit/rollback).
     */
    <T> T transacao(Supplier<T> operacao) {
        Connection con = db.getConexao();
        try {
            if (!con.getAutoCommit()) return operacao.get();
            con.setAutoCommit(false);
            try {
                T resultado = operacao.get();
                con.commit();
                return resultado;
            } catch (RuntimeException e) {
                try { con.rollback(); } catch (SQLException r) { e.addSuppressed(r); }
                throw e;
            } finally {
                con.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new DaoException("Erro na transacao", e);
        }
    }

    void transacao(Runnable operacao) {
        transacao(() -> { operacao.run(); return null; });
    }

    /** Registra na trilha de auditoria em nome do usuario logado. */
    void auditar(String acao, String entidade, Integer entidadeId) {
        auditar(acao, entidade, entidadeId, null);
    }

    void auditar(String acao, String entidade, Integer entidadeId, String detalhe) {
        logs.registrar(sessao == null ? null : sessao.usuario().getId(), acao, entidade, entidadeId, detalhe);
    }

    public void sair() {
        if (sessao != null) encerrarSessao("LOGOUT");
    }

    /**
     * Sigilo antes da trilha: a sessao (e a chave do prontuario) some primeiro; se o log falhar
     * (disco cheio, banco travado), a falha vai so para o log tecnico e o logout acontece igual.
     */
    private void encerrarSessao(String acao) {
        int usuarioId = sessao.usuario().getId();
        sessao = null;
        try {
            logs.registrar(usuarioId, acao, "usuario", usuarioId);
        } catch (RuntimeException e) {
            LogTecnico.erro("Contexto.encerrarSessao", e);
        }
    }

    @Override public void close() { db.close(); }
}
