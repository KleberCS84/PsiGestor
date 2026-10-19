package br.com.kleber.psigestor.service;

import br.com.kleber.psigestor.model.*;
import br.com.kleber.psigestor.util.RegraNegocioException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public class AgendaService {
    static final int DIAS_RETROATIVOS = 30;
    static final int ANOS_A_FRENTE = 1;
    public static final List<String> FORMAS_PAGAMENTO = List.of("PIX", "Dinheiro", "Cartao de debito",
            "Cartao de credito", "Transferencia", "Convenio");

    private final Contexto ctx;

    AgendaService(Contexto ctx) { this.ctx = ctx; }

    /**
     * Agenda uma sessao. Regras:
     *  - paciente ativo; profissional deve ser psicologo;
     *  - data entre 30 dias atras (lancar sessao ja feita) e 1 ano a frente;
     *  - sem sobreposicao de horario para o mesmo profissional nem para o mesmo paciente;
     *  - ONLINE exige consentimento especifico do paciente;
     *  - valor vem do convenio do paciente (se usarConvenio) ou do servico (particular).
     */
    public Atendimento agendar(int pacienteId, int profissionalId, int servicoId, LocalDateTime inicio,
                               Modalidade modalidade, boolean usarConvenio) {
        ctx.exigirSessao();
        if (inicio == null) throw new RegraNegocioException("Informe data e hora.");
        LocalDate hoje = ctx.hoje();
        if (inicio.toLocalDate().isBefore(hoje.minusDays(DIAS_RETROATIVOS))
                || inicio.toLocalDate().isAfter(hoje.plusYears(ANOS_A_FRENTE)))
            throw new RegraNegocioException("Data fora do período permitido (até " + DIAS_RETROATIVOS
                    + " dias atrás e " + ANOS_A_FRENTE + " ano à frente).");
        Paciente paciente = ctx.pacienteService.buscar(pacienteId);
        if (paciente.isAnonimizado())
            throw new RegraNegocioException("Paciente anonimizado (LGPD): não pode receber novos agendamentos.");
        if (paciente.isArquivado()) throw new RegraNegocioException("Paciente arquivado. Reative o cadastro antes.");

        Usuario prof = ctx.usuarios.listarPsicologos().stream()
                .filter(u -> u.getId() == profissionalId).findFirst()
                .orElseThrow(() -> new RegraNegocioException("Profissional inválido."));
        Servico servico = ctx.servicos.buscar(servicoId)
                .orElseThrow(() -> new RegraNegocioException("Serviço não encontrado."));

        LocalDateTime fim = inicio.plusMinutes(servico.getDuracaoMin());
        verificarConflitos(prof.getId(), prof.getNome(), pacienteId, paciente.getNome(), inicio, fim, null);

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
                throw new RegraNegocioException("Paciente não possui convênio cadastrado.");
            Convenio conv = ctx.convenios.listar().stream()
                    .filter(c -> c.getId().equals(paciente.getConvenioId())).findFirst()
                    .orElseThrow(() -> new RegraNegocioException("Convênio não encontrado."));
            a.setConvenioId(conv.getId());
            a.setValorCentavos(conv.getValorSessaoCentavos());
        } else {
            a.setValorCentavos(servico.getValorCentavos());
        }
        ctx.transacao(() -> {
            ctx.atendimentos.inserir(a);
            ctx.auditar("AGENDAMENTO", "atendimento", a.getId());
        });
        return a;
    }

    /** Segue StatusAtendimento.podeIrPara; a auditoria guarda o status anterior ("STATUS_DE->PARA"). */
    public void alterarStatus(int atendimentoId, StatusAtendimento status) {
        ctx.exigirSessao();
        if (status == null) throw new RegraNegocioException("Informe o status.");
        Atendimento a = ctx.atendimentos.buscar(atendimentoId)
                .orElseThrow(() -> new RegraNegocioException("Atendimento não encontrado."));
        StatusAtendimento anterior = a.getStatus();
        if (!anterior.podeIrPara(status))
            throw new RegraNegocioException("Não é possível mudar de " + anterior.rotulo() + " para " + status.rotulo() + ".");
        // o pagamento ja registrado e de uma sessao realizada: virar falta mudaria o que foi recebido
        if (anterior == StatusAtendimento.REALIZADO && status == StatusAtendimento.FALTOU && a.isPago())
            throw new RegraNegocioException("Atendimento realizado e pago não pode ser marcado como falta.");
        // cancelado nao ocupa horario: o espaco pode ter sido reaproveitado enquanto estava cancelado
        if (!anterior.ocupaHorario() && status.ocupaHorario())
            verificarConflitos(a.getProfissionalId(), a.getProfissionalNome(), a.getPacienteId(), a.getPacienteNome(),
                    a.getInicio(), a.getFim(), a.getId());
        ctx.transacao(() -> {
            if (status == StatusAtendimento.REALIZADO || status == StatusAtendimento.FALTOU) {
                // repasse congelado agora: mudar o % depois nao reescreve periodos ja fechados (M-5)
                double percentual = ctx.usuarios.buscar(a.getProfissionalId())
                        .orElseThrow(() -> new RegraNegocioException("Profissional não encontrado."))
                        .getPercentualRepasseClinica();
                ctx.atendimentos.atualizarStatus(atendimentoId, status, percentual);
            } else {
                ctx.atendimentos.atualizarStatus(atendimentoId, status);
            }
            ctx.auditar("STATUS_" + anterior.name() + "->" + status.name(), "atendimento", atendimentoId);
        });
    }

    private void verificarConflitos(int profissionalId, String profissionalNome, int pacienteId, String pacienteNome,
                                    LocalDateTime inicio, LocalDateTime fim, Integer ignorarId) {
        if (ctx.atendimentos.contarConflitos(profissionalId, inicio, fim, ignorarId) > 0)
            throw new RegraNegocioException("Conflito: " + profissionalNome + " já tem atendimento nesse horário.");
        if (ctx.atendimentos.contarConflitosPaciente(pacienteId, inicio, fim, ignorarId) > 0)
            throw new RegraNegocioException("Conflito: " + pacienteNome + " já tem atendimento nesse horário.");
    }

    /** Pagar antes (agendado/confirmado) ou cobrar falta e permitido; cancelado ou ja pago, nao. */
    public void registrarPagamento(int atendimentoId, String forma) {
        ctx.exigirSessao();
        if (forma == null || !FORMAS_PAGAMENTO.contains(forma))
            throw new RegraNegocioException("Forma de pagamento inválida.");
        Atendimento a = ctx.atendimentos.buscar(atendimentoId)
                .orElseThrow(() -> new RegraNegocioException("Atendimento não encontrado."));
        if (a.getStatus() == StatusAtendimento.CANCELADO)
            throw new RegraNegocioException("Atendimento cancelado não pode receber pagamento.");
        if (a.isPago())   // sobrescrever apagaria a forma original sem rastro
            throw new RegraNegocioException("Pagamento já registrado para este atendimento.");
        ctx.transacao(() -> {
            ctx.atendimentos.registrarPagamento(atendimentoId, forma);
            ctx.auditar("PAGAMENTO", "atendimento", atendimentoId);
        });
    }

    /** O recibo e emitido FORA do sistema (app Receita Saude); aqui so marcamos que foi feito. */
    public void marcarReciboEmitido(int atendimentoId) {
        ctx.exigirSessao();
        ctx.transacao(() -> {
            ctx.atendimentos.marcarReciboEmitido(atendimentoId);
            ctx.auditar("RECIBO_EMITIDO", "atendimento", atendimentoId);
        });
    }

    public List<Atendimento> doDia(LocalDate dia, Integer profissionalId) {
        ctx.exigirSessao();
        return ctx.atendimentos.listarPeriodo(dia.atStartOfDay(), dia.plusDays(1).atStartOfDay(), profissionalId);
    }

    public List<Atendimento> periodo(LocalDate de, LocalDate ateInclusive, Integer profissionalId) {
        ctx.exigirSessao();
        return ctx.atendimentos.listarPeriodo(de.atStartOfDay(), ateInclusive.plusDays(1).atStartOfDay(), profissionalId);
    }
}
