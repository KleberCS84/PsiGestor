package br.com.kleber.psigestor.service;

import br.com.kleber.psigestor.dao.*;
import br.com.kleber.psigestor.util.Database;
import br.com.kleber.psigestor.util.RegraNegocioException;
import br.com.kleber.psigestor.util.Sessao;

import java.time.Clock;
import java.time.LocalDate;

/**
 * "Caixa de ferramentas" da aplicacao: cria os DAOs e services uma unica vez
 * e guarda a sessao do usuario logado (injecao de dependencia feita a mao).
 */
public class Contexto implements AutoCloseable {

    private final Database db;
    private Sessao sessao;
    private Clock relogio = Clock.systemDefaultZone();

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
        backup = new BackupService(this, con);
    }

    public Sessao getSessao() { return sessao; }

    /** Somente para testes automatizados (verificar o que foi gravado de fato no banco). */
    java.sql.Connection getConexaoParaTeste() { return db.getConexao(); }

    void setSessao(Sessao sessao) { this.sessao = sessao; }

    /** Data de hoje pelo relogio do contexto (fixavel nos testes). */
    LocalDate hoje() { return LocalDate.now(relogio); }

    /** Somente para testes automatizados (datas fixas nao "vencem" com o passar do tempo). */
    void setRelogio(Clock relogio) { this.relogio = relogio; }

    /** Garante que ha alguem logado; devolve a sessao. */
    Sessao exigirSessao() {
        if (sessao == null) throw new RegraNegocioException("Faca login para continuar.");
        return sessao;
    }

    /** Registra na trilha de auditoria em nome do usuario logado. */
    void auditar(String acao, String entidade, Integer entidadeId) {
        logs.registrar(sessao == null ? null : sessao.usuario().getId(), acao, entidade, entidadeId);
    }

    public void sair() {
        if (sessao != null) auditar("LOGOUT", "usuario", sessao.usuario().getId());
        sessao = null;
    }

    @Override public void close() { db.close(); }
}
