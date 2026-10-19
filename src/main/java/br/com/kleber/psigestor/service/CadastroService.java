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
        if (Validadores.vazio(s.getNome())) throw new RegraNegocioException("Informe o nome do serviço.");
        if (s.getDuracaoMin() <= 0) throw new RegraNegocioException("A duração deve ser maior que zero.");
        validarValor(s.getValorCentavos());
        boolean novo = s.getId() == null;
        ctx.transacao(() -> {
            ctx.servicos.salvar(s);
            ctx.auditar(novo ? "CADASTRO_SERVICO" : "ALTERACAO_SERVICO", "servico", s.getId());
        });
        return s;
    }

    public Convenio salvarConvenio(Convenio c) {
        exigirPsicologo();
        if (Validadores.vazio(c.getNome())) throw new RegraNegocioException("Informe o nome do convênio.");
        validarValor(c.getValorSessaoCentavos());
        if (c.getPrazoPagamentoDias() < 0) throw new RegraNegocioException("O prazo não pode ser negativo.");
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

    /** Teto por sessao: um digito a mais por engano (R$ 2.000.000,00) nao entra no faturamento. */
    static final long VALOR_MAXIMO_CENTAVOS = 10_000_000;   // R$ 100.000,00

    private static void validarValor(long centavos) {
        if (centavos < 0) throw new RegraNegocioException("O valor não pode ser negativo.");
        if (centavos > VALOR_MAXIMO_CENTAVOS) throw new RegraNegocioException("O valor máximo é R$ 100.000,00.");
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
            throw new RegraNegocioException("Somente psicólogos alteram valores e repasse.");
    }
}
