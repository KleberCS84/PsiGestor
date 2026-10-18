package br.com.kleber.psigestor.service;

import br.com.kleber.psigestor.model.Convenio;
import br.com.kleber.psigestor.model.Servico;
import br.com.kleber.psigestor.model.Usuario;
import br.com.kleber.psigestor.util.RegraNegocioException;
import br.com.kleber.psigestor.util.Validadores;

import java.util.List;

/**
 * Servicos, convenios e % de repasse. Todos definem dinheiro (valor cobrado ou dividido entre
 * profissionais), por isso so o psicologo altera e toda alteracao vai para a trilha de auditoria.
 */
public class CadastroService {
    private final Contexto ctx;

    CadastroService(Contexto ctx) { this.ctx = ctx; }

    public Servico salvarServico(Servico s) {
        exigirPsicologo();
        if (Validadores.vazio(s.getNome())) throw new RegraNegocioException("Informe o nome do servico.");
        if (s.getDuracaoMin() <= 0) throw new RegraNegocioException("A duracao deve ser maior que zero.");
        if (s.getValorCentavos() < 0) throw new RegraNegocioException("O valor nao pode ser negativo.");
        boolean novo = s.getId() == null;
        ctx.transacao(() -> {
            ctx.servicos.salvar(s);
            ctx.auditar(novo ? "CADASTRO_SERVICO" : "ALTERACAO_SERVICO", "servico", s.getId());
        });
        return s;
    }

    public Convenio salvarConvenio(Convenio c) {
        exigirPsicologo();
        if (Validadores.vazio(c.getNome())) throw new RegraNegocioException("Informe o nome do convenio.");
        if (c.getValorSessaoCentavos() < 0) throw new RegraNegocioException("O valor nao pode ser negativo.");
        if (c.getPrazoPagamentoDias() < 0) throw new RegraNegocioException("O prazo nao pode ser negativo.");
        boolean novo = c.getId() == null;
        ctx.transacao(() -> {
            ctx.convenios.salvar(c);
            ctx.auditar(novo ? "CADASTRO_CONVENIO" : "ALTERACAO_CONVENIO", "convenio", c.getId());
        });
        return c;
    }

    public void atualizarRepasse(int usuarioId, double percentualClinica) {
        exigirPsicologo();
        validarPercentual(percentualClinica);
        ctx.transacao(() -> {
            ctx.usuarios.atualizarRepasse(usuarioId, percentualClinica);
            ctx.auditar("ALTERACAO_REPASSE", "usuario", usuarioId);
        });
    }

    /** Mesma faixa do CHECK do banco, com mensagem clara (tambem usada no cadastro de usuario). */
    static void validarPercentual(double percentualClinica) {
        if (Double.isNaN(percentualClinica) || percentualClinica < 0 || percentualClinica > 100)
            throw new RegraNegocioException("Use um valor entre 0 e 100.");
    }

    public List<Servico> listarServicos() {
        ctx.exigirSessao();
        return ctx.servicos.listar();
    }

    public List<Convenio> listarConvenios() {
        ctx.exigirSessao();
        return ctx.convenios.listar();
    }

    public List<Usuario> listarUsuarios() {
        ctx.exigirSessao();
        return ctx.usuarios.listar();
    }

    public List<Usuario> listarPsicologos() {
        ctx.exigirSessao();
        return ctx.usuarios.listarPsicologos();
    }

    private void exigirPsicologo() {
        if (!ctx.exigirSessao().usuario().isPsicologo())
            throw new RegraNegocioException("Somente psicologos alteram valores e repasse.");
    }
}
