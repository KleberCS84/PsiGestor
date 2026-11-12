package br.com.kleber.psigestor.service;

import br.com.kleber.psigestor.model.*;
import br.com.kleber.psigestor.util.RegraNegocioException;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class AgendaService {
    static final int DIAS_RETROATIVOS = 30;
    static final int ANOS_A_FRENTE = 1;
    public static final int DURACAO_MAXIMA_MIN = 480;
    public static final int OBSERVACAO_MAXIMA = 500;
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
        return agendar(pacienteId, profissionalId, servicoId, inicio, null, modalidade, usarConvenio, null);
    }

    /** duracaoMin null = a duracao do servico. */
    public Atendimento agendar(int pacienteId, int profissionalId, int servicoId, LocalDateTime inicio,
                               Integer duracaoMin, Modalidade modalidade, boolean usarConvenio, String observacao) {
        ctx.exigirSessao();
        validarJanela(inicio);
        Paciente paciente = pacienteAgendavel(pacienteId);

        Usuario prof = ctx.usuarios.listarPsicologos().stream()
                .filter(u -> u.getId() == profissionalId).findFirst()
                .orElseThrow(() -> new RegraNegocioException("Profissional inválido."));
        Servico servico = buscarServico(servicoId);
        String obs = validarObservacao(observacao);

        LocalDateTime fim = inicio.plusMinutes(validarDuracao(duracaoMin == null ? servico.getDuracaoMin() : duracaoMin));
        verificarConflitos(prof.getId(), prof.getNome(), pacienteId, paciente.getNome(), inicio, fim, null);
        exigirConsentimentoOnline(pacienteId, modalidade);

        Atendimento a = new Atendimento();
        a.setObservacao(obs);
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

    /** So agendado/confirmado: realizado, falta e cancelado ja contam (ou deixaram de contar) no financeiro. */
    public static boolean podeEditar(Atendimento a) {
        return a.getStatus() == StatusAtendimento.AGENDADO || a.getStatus() == StatusAtendimento.CONFIRMADO;
    }

    /** Mesma mensagem na tela (antes de abrir o formulario) e no service. */
    public static void exigirEditavel(Atendimento a) {
        if (!podeEditar(a))
            throw new RegraNegocioException("Atendimento com status \"" + a.getStatus().rotulo()
                    + "\" não pode ser editado: já afeta o financeiro. Só agendados e confirmados podem mudar.");
    }

    /** Edita sem trocar o paciente (ver a versao com novoPacienteId). */
    public Atendimento editar(int atendimentoId, int servicoId, LocalDateTime inicio, int duracaoMin,
                              Modalidade modalidade, String observacao) {
        return editar(atendimentoId, null, servicoId, inicio, duracaoMin, modalidade, observacao);
    }

    /**
     * Motivo pelo qual o paciente deste atendimento NAO pode ser trocado, ou vazio se pode.
     * So troca quem esta agendado/confirmado e ainda nao tem registro de prontuario nem lancamento
     * financeiro (pagamento ou recibo): depois disso o atendimento e historico do paciente original.
     * A tela usa o texto para explicar o campo desabilitado; editar() aplica a mesma regra.
     */
    public Optional<String> motivoBloqueioTrocaPaciente(int atendimentoId) {
        ctx.exigirSessao();
        return Optional.ofNullable(motivoBloqueioTrocaPaciente(buscarAtendimento(atendimentoId)));
    }

    private String motivoBloqueioTrocaPaciente(Atendimento a) {
        if (!podeEditar(a))
            return "Só atendimentos agendados ou confirmados permitem trocar o paciente (este está \""
                    + a.getStatus().rotulo() + "\").";
        if (ctx.evolucoes.existePorAtendimento(a.getId()))
            return "Já existe registro de prontuário ligado a este atendimento.";
        if (a.isPago() || a.isReciboEmitido() || a.getFormaPagamento() != null)
            return "Já existe lançamento financeiro (pagamento ou recibo) ligado a este atendimento.";
        return null;
    }

    private Atendimento buscarAtendimento(int atendimentoId) {
        return ctx.atendimentos.buscar(atendimentoId)
                .orElseThrow(() -> new RegraNegocioException("Atendimento não encontrado."));
    }

    /**
     * Edita paciente, data/hora, duracao, servico, modalidade e observacao. O profissional nao muda
     * (para isso, cancelar e agendar outro). novoPacienteId null (ou igual ao atual) = paciente mantido;
     * a troca segue motivoBloqueioTrocaPaciente e, se o atendimento e por convenio, o novo paciente precisa
     * ter o mesmo convenio. Revalida tudo como em agendar (inclusive conflitos de horario do NOVO paciente),
     * ignorando o proprio horario. Servico trocado recalcula o valor particular, exceto se ja pago;
     * convenio continua com o valor dele. Troca de paciente grava EDICAO_ATENDIMENTO_PACIENTE (so ids).
     */
    public Atendimento editar(int atendimentoId, Integer novoPacienteId, int servicoId, LocalDateTime inicio,
                              int duracaoMin, Modalidade modalidade, String observacao) {
        ctx.exigirSessao();
        Atendimento a = buscarAtendimento(atendimentoId);
        exigirEditavel(a);
        if (modalidade == null) throw new RegraNegocioException("Informe a modalidade.");
        validarJanela(inicio);
        int pacienteAnteriorId = a.getPacienteId();
        boolean trocouPaciente = novoPacienteId != null && novoPacienteId != pacienteAnteriorId;
        if (trocouPaciente) {
            String motivo = motivoBloqueioTrocaPaciente(a);
            if (motivo != null) throw new RegraNegocioException("Não é possível trocar o paciente. " + motivo);
        }
        Paciente paciente = pacienteAgendavel(trocouPaciente ? novoPacienteId : pacienteAnteriorId);
        if (trocouPaciente && a.getConvenioId() != null && !a.getConvenioId().equals(paciente.getConvenioId()))
            throw new RegraNegocioException("Não é possível trocar o paciente. Este atendimento é cobrado pelo "
                    + "convênio e o novo paciente não tem o mesmo convênio.");
        Servico servico = buscarServico(servicoId);
        String obs = validarObservacao(observacao);
        LocalDateTime fim = inicio.plusMinutes(validarDuracao(duracaoMin));
        verificarConflitos(a.getProfissionalId(), a.getProfissionalNome(), paciente.getId(), paciente.getNome(),
                inicio, fim, a.getId());
        exigirConsentimentoOnline(paciente.getId(), modalidade);

        String detalhe = detalheAlteracao(a, servico, inicio, fim, modalidade, obs);
        boolean trocouServico = a.getServicoId() != servicoId;
        if (trocouServico && !a.isPago() && a.getConvenioId() == null) {
            long anterior = a.getValorCentavos();
            a.setValorCentavos(servico.getValorCentavos());
            if (anterior != a.getValorCentavos())
                detalhe += "; valor: " + anterior + " -> " + a.getValorCentavos() + " centavos";
        }
        a.setPacienteId(paciente.getId());
        a.setPacienteNome(paciente.getNome());
        a.setServicoId(servicoId);
        a.setServicoNome(servico.getNome());
        a.setInicio(inicio);
        a.setFim(fim);
        a.setModalidade(modalidade);
        a.setObservacao(obs);
        String detalheFinal = detalhe;
        ctx.transacao(() -> {
            ctx.atendimentos.atualizarAgendamento(a);
            ctx.auditar("ALTERACAO_ATENDIMENTO", "atendimento", a.getId(), detalheFinal);
            // so ids: o log nao guarda nome de paciente
            if (trocouPaciente)
                ctx.auditar("EDICAO_ATENDIMENTO_PACIENTE", "atendimento", a.getId(),
                        "paciente_id: " + pacienteAnteriorId + " -> " + paciente.getId());
        });
        return a;
    }

    /** Antes e depois de data/hora e servico, sempre; o resto so se mudou. Nunca o nome do paciente. */
    private static String detalheAlteracao(Atendimento a, Servico novoServico, LocalDateTime inicio,
                                           LocalDateTime fim, Modalidade modalidade, String obs) {
        DateTimeFormatter f = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
        StringBuilder d = new StringBuilder()
                .append("data: ").append(a.getInicio().format(f)).append(" -> ").append(inicio.format(f))
                .append("; servico: ").append(a.getServicoNome()).append(" -> ").append(novoServico.getNome());
        long duracaoAntes = Duration.between(a.getInicio(), a.getFim()).toMinutes();
        long duracaoDepois = Duration.between(inicio, fim).toMinutes();
        if (duracaoAntes != duracaoDepois) d.append("; duracao: ").append(duracaoAntes).append(" -> ").append(duracaoDepois);
        if (a.getModalidade() != modalidade) d.append("; modalidade: ").append(a.getModalidade()).append(" -> ").append(modalidade);
        if (!Objects.equals(a.getObservacao(), obs)) d.append("; observacao alterada");
        return d.toString();
    }

    private void validarJanela(LocalDateTime inicio) {
        if (inicio == null) throw new RegraNegocioException("Informe data e hora.");
        LocalDate hoje = ctx.hoje();
        if (inicio.toLocalDate().isBefore(hoje.minusDays(DIAS_RETROATIVOS))
                || inicio.toLocalDate().isAfter(hoje.plusYears(ANOS_A_FRENTE)))
            throw new RegraNegocioException("Data fora do período permitido (até " + DIAS_RETROATIVOS
                    + " dias atrás e " + ANOS_A_FRENTE + " ano à frente).");
    }

    private Paciente pacienteAgendavel(int pacienteId) {
        Paciente paciente = ctx.pacienteService.buscar(pacienteId);
        if (paciente.isAnonimizado())
            throw new RegraNegocioException("Paciente anonimizado (LGPD): não pode receber novos agendamentos.");
        if (paciente.isArquivado()) throw new RegraNegocioException("Paciente arquivado. Reative o cadastro antes.");
        return paciente;
    }

    private Servico buscarServico(int servicoId) {
        return ctx.servicos.buscar(servicoId)
                .orElseThrow(() -> new RegraNegocioException("Serviço não encontrado."));
    }

    private static int validarDuracao(int minutos) {
        if (minutos < 1 || minutos > DURACAO_MAXIMA_MIN)
            throw new RegraNegocioException("Duração deve ficar entre 1 e " + DURACAO_MAXIMA_MIN + " minutos.");
        return minutos;
    }

    /** Vazia vira null; em claro e visivel a secretaria, por isso curta. */
    private static String validarObservacao(String observacao) {
        if (observacao == null || observacao.isBlank()) return null;
        String t = observacao.trim();
        if (t.length() > OBSERVACAO_MAXIMA)
            throw new RegraNegocioException("Observação com no máximo " + OBSERVACAO_MAXIMA + " caracteres.");
        return t;
    }

    private void exigirConsentimentoOnline(int pacienteId, Modalidade modalidade) {
        if (modalidade == Modalidade.ONLINE
                && !ctx.pacienteService.temConsentimento(pacienteId, TipoConsentimento.ATENDIMENTO_ONLINE))
            throw new RegraNegocioException("Registre o consentimento para atendimento online antes de agendar.");
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
