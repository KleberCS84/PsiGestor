package br.com.kleber.psigestor.service;

import br.com.kleber.psigestor.model.*;
import br.com.kleber.psigestor.util.RegraNegocioException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public class AgendaService {
    private final Contexto ctx;

    AgendaService(Contexto ctx) { this.ctx = ctx; }

    /**
     * Agenda uma sessao. Regras:
     *  - paciente ativo; profissional deve ser psicologo;
     *  - sem sobreposicao de horario para o mesmo profissional;
     *  - ONLINE exige consentimento especifico do paciente;
     *  - valor vem do convenio do paciente (se usarConvenio) ou do servico (particular).
     */
    public Atendimento agendar(int pacienteId, int profissionalId, int servicoId, LocalDateTime inicio,
                               Modalidade modalidade, boolean usarConvenio) {
        ctx.exigirSessao();
        if (inicio == null) throw new RegraNegocioException("Informe data e hora.");
        Paciente paciente = ctx.pacienteService.buscar(pacienteId);
        if (paciente.isArquivado()) throw new RegraNegocioException("Paciente arquivado. Reative o cadastro antes.");

        Usuario prof = ctx.usuarios.listarPsicologos().stream()
                .filter(u -> u.getId() == profissionalId).findFirst()
                .orElseThrow(() -> new RegraNegocioException("Profissional invalido."));
        Servico servico = ctx.servicos.buscar(servicoId)
                .orElseThrow(() -> new RegraNegocioException("Servico nao encontrado."));

        LocalDateTime fim = inicio.plusMinutes(servico.getDuracaoMin());
        if (ctx.atendimentos.contarConflitos(prof.getId(), inicio, fim, null) > 0)
            throw new RegraNegocioException("Conflito: " + prof.getNome() + " ja tem atendimento nesse horario.");

        if (modalidade == Modalidade.ONLINE
                && !ctx.pacienteService.temConsentimento(pacienteId, TipoConsentimento.ATENDIMENTO_ONLINE))
            throw new RegraNegocioException("Registre o consentimento para atendimento online antes de agendar.");

        Atendimento a = new Atendimento();
        a.setPacienteId(pacienteId);
        a.setProfissionalId(prof.getId());
        a.setServicoId(servicoId);
        a.setInicio(inicio);
        a.setFim(fim);
        a.setModalidade(modalidade);
        if (usarConvenio) {
            if (paciente.getConvenioId() == null)
                throw new RegraNegocioException("Paciente nao possui convenio cadastrado.");
            Convenio conv = ctx.convenios.listar().stream()
                    .filter(c -> c.getId().equals(paciente.getConvenioId())).findFirst()
                    .orElseThrow(() -> new RegraNegocioException("Convenio nao encontrado."));
            a.setConvenioId(conv.getId());
            a.setValor(conv.getValorSessao());
        } else {
            a.setValor(servico.getValor());
        }
        ctx.atendimentos.inserir(a);
        ctx.auditar("AGENDAMENTO", "atendimento", a.getId());
        return a;
    }

    public void alterarStatus(int atendimentoId, StatusAtendimento status) {
        ctx.exigirSessao();
        ctx.atendimentos.atualizarStatus(atendimentoId, status);
        ctx.auditar("STATUS_" + status.name(), "atendimento", atendimentoId);
    }

    public void registrarPagamento(int atendimentoId, String forma) {
        ctx.exigirSessao();
        ctx.atendimentos.registrarPagamento(atendimentoId, forma);
        ctx.auditar("PAGAMENTO", "atendimento", atendimentoId);
    }

    /** O recibo e emitido FORA do sistema (app Receita Saude); aqui so marcamos que foi feito. */
    public void marcarReciboEmitido(int atendimentoId) {
        ctx.exigirSessao();
        ctx.atendimentos.marcarReciboEmitido(atendimentoId);
        ctx.auditar("RECIBO_EMITIDO", "atendimento", atendimentoId);
    }

    public List<Atendimento> doDia(LocalDate dia, Integer profissionalId) {
        return ctx.atendimentos.listarPeriodo(dia.atStartOfDay(), dia.plusDays(1).atStartOfDay(), profissionalId);
    }

    public List<Atendimento> periodo(LocalDate de, LocalDate ateInclusive, Integer profissionalId) {
        return ctx.atendimentos.listarPeriodo(de.atStartOfDay(), ateInclusive.plusDays(1).atStartOfDay(), profissionalId);
    }
}
