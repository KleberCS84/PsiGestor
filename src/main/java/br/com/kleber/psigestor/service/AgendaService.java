package br.com.kleber.psigestor.service;

import br.com.kleber.psigestor.model.*;
import br.com.kleber.psigestor.util.RegraNegocioException;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

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
        aplicarValor(a, paciente, servico, usarConvenio);
        ctx.transacao(() -> {
            ctx.atendimentos.inserir(a);
            ctx.auditar("AGENDAMENTO", "atendimento", a.getId());
        });
        return a;
    }

    /** Valor do convenio do paciente (usarConvenio) ou do servico (particular). */
    private void aplicarValor(Atendimento a, Paciente paciente, Servico servico, boolean usarConvenio) {
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
    }

    // ------------------------------------------------------------------ agendamento em lote (series)

    public static final int LOTE_MINIMO = 2;
    public static final int LOTE_MAXIMO = 52;
    private static final DateTimeFormatter DATA_HORA_MSG = DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm");

    public enum Frequencia {
        SEMANAL(1, "Semanal"), QUINZENAL(2, "Quinzenal");
        private final int semanas;
        private final String rotulo;
        Frequencia(int semanas, String rotulo) { this.semanas = semanas; this.rotulo = rotulo; }
        public int semanas() { return semanas; }
        public String rotulo() { return rotulo; }
    }

    /** Alcance de um cancelamento/edicao de sessao que faz parte de uma serie. */
    public enum EscopoSerie { SO_ESTA, ESTA_E_PROXIMAS }

    public enum SituacaoPrevia { OK, CONFLITO, INVALIDA }

    /** Uma linha da previa: numero (1..n), horario e se pode ser salva como esta. */
    public record ItemPrevia(int numero, LocalDateTime inicio, LocalDateTime fim, SituacaoPrevia situacao, String motivo) {
        public boolean ok() { return situacao == SituacaoPrevia.OK; }
    }

    /** O que e igual em todas as sessoes da serie; as datas vao a parte. duracaoMin null = a do servico. */
    public record ParametrosLote(int pacienteId, int profissionalId, int servicoId, Integer duracaoMin,
                                 Modalidade modalidade, boolean usarConvenio, String observacao) { }

    private record Base(Paciente paciente, Usuario prof, Servico servico, String obs, int duracao,
                        Modalidade modalidade, boolean usarConvenio) { }

    /** A primeira data e a escolhida; as outras somam 1 (semanal) ou 2 (quinzenal) semanas por sessao. */
    public static List<LocalDateTime> gerarDatas(LocalDateTime primeira, Frequencia frequencia, int total) {
        if (primeira == null) throw new RegraNegocioException("Informe data e hora.");
        if (frequencia == null) throw new RegraNegocioException("Informe a frequência.");
        if (total < LOTE_MINIMO || total > LOTE_MAXIMO)
            throw new RegraNegocioException("O número total de sessões deve ficar entre " + LOTE_MINIMO
                    + " e " + LOTE_MAXIMO + ".");
        List<LocalDateTime> datas = new ArrayList<>(total);
        for (int i = 0; i < total; i++) datas.add(primeira.plusWeeks((long) i * frequencia.semanas()));
        return datas;
    }

    private Base prepararBase(ParametrosLote p) {
        if (p.modalidade() == null) throw new RegraNegocioException("Informe a modalidade.");
        Paciente paciente = pacienteAgendavel(p.pacienteId());
        Usuario prof = ctx.usuarios.listarPsicologos().stream()
                .filter(u -> u.getId() == p.profissionalId()).findFirst()
                .orElseThrow(() -> new RegraNegocioException("Profissional inválido."));
        Servico servico = buscarServico(p.servicoId());
        String obs = validarObservacao(p.observacao());
        int duracao = validarDuracao(p.duracaoMin() == null ? servico.getDuracaoMin() : p.duracaoMin());
        exigirConsentimentoOnline(p.pacienteId(), p.modalidade());
        if (p.usarConvenio()) aplicarValor(new Atendimento(), paciente, servico, true);   // so valida o convenio
        return new Base(paciente, prof, servico, obs, duracao, p.modalidade(), p.usarConvenio());
    }

    /**
     * Previa do lote: nada e gravado. Cada data vira OK, CONFLITO (profissional ou paciente ja ocupado, ou
     * sobreposta a outra sessao da propria serie) ou INVALIDA (fora do periodo permitido).
     */
    public List<ItemPrevia> previa(ParametrosLote p, List<LocalDateTime> inicios) {
        ctx.exigirSessao();
        if (inicios == null || inicios.isEmpty()) throw new RegraNegocioException("Nenhuma sessão para agendar.");
        if (inicios.size() > LOTE_MAXIMO)
            throw new RegraNegocioException("No máximo " + LOTE_MAXIMO + " sessões por série.");
        Base b = prepararBase(p);
        List<ItemPrevia> itens = new ArrayList<>();
        for (int i = 0; i < inicios.size(); i++) {
            LocalDateTime inicio = inicios.get(i);
            if (inicio == null) throw new RegraNegocioException("Informe data e hora da sessão " + (i + 1) + ".");
            LocalDateTime fim = inicio.plusMinutes(b.duracao());
            try {
                validarJanela(inicio);
            } catch (RegraNegocioException e) {
                itens.add(new ItemPrevia(i + 1, inicio, fim, SituacaoPrevia.INVALIDA, e.getMessage()));
                continue;
            }
            String motivo = null;
            if (ctx.atendimentos.contarConflitos(b.prof().getId(), inicio, fim, null) > 0)
                motivo = "Profissional já tem atendimento nesse horário.";
            else if (ctx.atendimentos.contarConflitosPaciente(b.paciente().getId(), inicio, fim, null) > 0)
                motivo = "Paciente já tem atendimento nesse horário.";
            else {
                for (ItemPrevia anterior : itens) {
                    if (anterior.situacao() != SituacaoPrevia.INVALIDA
                            && anterior.inicio().isBefore(fim) && inicio.isBefore(anterior.fim())) {
                        motivo = "Sobrepõe a sessão " + anterior.numero() + " desta série.";
                        break;
                    }
                }
            }
            itens.add(new ItemPrevia(i + 1, inicio, fim,
                    motivo == null ? SituacaoPrevia.OK : SituacaoPrevia.CONFLITO, motivo));
        }
        return itens;
    }

    /**
     * Grava a serie inteira em UMA transacao: se qualquer sessao falhar (conflito, fora do periodo...), nenhuma
     * fica gravada. Numera 1..n na ordem recebida, grava um unico log AGENDAMENTO_LOTE (so ids e quantidade).
     * "Pular conflitos" e feito por quem chama, tirando da lista as datas que a previa marcou.
     */
    public List<Atendimento> agendarLote(ParametrosLote p, List<LocalDateTime> inicios) {
        ctx.exigirSessao();
        if (inicios == null || inicios.isEmpty()) throw new RegraNegocioException("Nenhuma sessão para agendar.");
        if (inicios.size() > LOTE_MAXIMO)
            throw new RegraNegocioException("No máximo " + LOTE_MAXIMO + " sessões por série.");
        Base b = prepararBase(p);
        String serieId = UUID.randomUUID().toString();
        List<Atendimento> criados = new ArrayList<>();
        ctx.transacao(() -> {
            for (int i = 0; i < inicios.size(); i++) {
                LocalDateTime inicio = inicios.get(i);
                if (inicio == null) throw new RegraNegocioException("Informe data e hora da sessão " + (i + 1) + ".");
                LocalDateTime fim = inicio.plusMinutes(b.duracao());
                try {
                    validarJanela(inicio);
                    verificarConflitos(b.prof().getId(), b.prof().getNome(), b.paciente().getId(),
                            b.paciente().getNome(), inicio, fim, null);
                } catch (RegraNegocioException e) {
                    throw new RegraNegocioException("Sessão " + (i + 1) + " (" + inicio.format(DATA_HORA_MSG) + "): "
                            + e.getMessage() + " Nenhuma sessão foi salva.");
                }
                Atendimento a = new Atendimento();
                a.setPacienteId(b.paciente().getId());
                a.setProfissionalId(b.prof().getId());
                a.setServicoId(b.servico().getId());
                a.setInicio(inicio);
                a.setFim(fim);
                a.setModalidade(b.modalidade());
                a.setObservacao(b.obs());
                a.setSerieId(serieId);
                a.setNumeroNaSerie(i + 1);
                aplicarValor(a, b.paciente(), b.servico(), b.usarConvenio());
                ctx.atendimentos.inserir(a);
                criados.add(a);
            }
            // so ids e contagem: o log nao guarda nome de paciente
            ctx.auditar("AGENDAMENTO_LOTE", "paciente", p.pacienteId(),
                    "serie_id: " + serieId + "; quantidade: " + inicios.size());
        });
        return criados;
    }

    /** Quantas sessoes futuras (agendadas/confirmadas) a serie deste atendimento ainda tem depois dele; 0 se avulso. */
    public int contarProximosDaSerie(int atendimentoId) {
        ctx.exigirSessao();
        Atendimento a = buscarAtendimento(atendimentoId);
        if (!a.isDeSerie()) return 0;
        return ctx.atendimentos.listarProximosDaSerie(a.getSerieId(), a.getInicio()).size();
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
        if (a.isDeSerie()) return "Faz parte de uma série.";
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

    /**
     * Edita uma sessao de serie com escopo. ESTA_E_PROXIMAS: as proximas (mesma serie, data futura, agendadas ou
     * confirmadas) recebem os mesmos servico/duracao/modalidade/observacao e o horario deslocado pelo mesmo
     * intervalo da sessao editada. Tudo em uma transacao; se uma falhar, a mensagem diz a data e hora e nada muda.
     * Sem serie ou SO_ESTA = editar normal.
     */
    public Atendimento editar(int atendimentoId, Integer novoPacienteId, int servicoId, LocalDateTime inicio,
                              int duracaoMin, Modalidade modalidade, String observacao, EscopoSerie escopo) {
        ctx.exigirSessao();
        Atendimento a = buscarAtendimento(atendimentoId);
        if (escopo != EscopoSerie.ESTA_E_PROXIMAS || !a.isDeSerie())
            return editar(atendimentoId, novoPacienteId, servicoId, inicio, duracaoMin, modalidade, observacao);
        exigirEditavel(a);
        if (inicio == null) throw new RegraNegocioException("Informe data e hora.");
        Duration delta = Duration.between(a.getInicio(), inicio);
        List<Atendimento> grupo = new ArrayList<>();
        grupo.add(a);
        grupo.addAll(ctx.atendimentos.listarProximosDaSerie(a.getSerieId(), a.getInicio()));
        // deslocando para frente, a mais tardia vai primeiro (e ao contrario): uma sessao nunca cai no horario
        // de outra da mesma serie que ainda nao saiu do lugar
        if (!delta.isNegative()) Collections.reverse(grupo);
        Atendimento[] editado = new Atendimento[1];
        ctx.transacao(() -> {
            for (Atendimento s : grupo) {
                LocalDateTime novo = s.getInicio().plus(delta);
                boolean atual = s.getId().equals(atendimentoId);
                try {
                    Atendimento r = editar(s.getId(), atual ? novoPacienteId : null, servicoId, novo, duracaoMin,
                            modalidade, observacao);
                    if (atual) editado[0] = r;
                } catch (RegraNegocioException e) {
                    throw new RegraNegocioException("Não foi possível alterar a sessão de " + novo.format(DATA_HORA_MSG)
                            + ": " + e.getMessage() + " Nenhuma sessão da série foi alterada.");
                }
            }
        });
        return editado[0];
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

    /**
     * Cancelamento de sessao de serie com escopo. ESTA_E_PROXIMAS so vale para CANCELADO e atinge apenas as
     * proximas agendadas/confirmadas (realizadas, faltas, canceladas e anteriores ficam como estao).
     * Uma transacao: se alguma falhar, nenhuma muda.
     */
    public void alterarStatus(int atendimentoId, StatusAtendimento status, EscopoSerie escopo) {
        ctx.exigirSessao();
        Atendimento a = buscarAtendimento(atendimentoId);
        if (escopo != EscopoSerie.ESTA_E_PROXIMAS || !a.isDeSerie()) {
            alterarStatus(atendimentoId, status);
            return;
        }
        if (status != StatusAtendimento.CANCELADO)
            throw new RegraNegocioException("Só o cancelamento pode ser aplicado a toda a série.");
        List<Atendimento> grupo = new ArrayList<>();
        grupo.add(a);
        grupo.addAll(ctx.atendimentos.listarProximosDaSerie(a.getSerieId(), a.getInicio()));
        ctx.transacao(() -> {
            for (Atendimento s : grupo) {
                try {
                    alterarStatus(s.getId(), status);
                } catch (RegraNegocioException e) {
                    throw new RegraNegocioException("Não foi possível cancelar a sessão de "
                            + s.getInicio().format(DATA_HORA_MSG) + ": " + e.getMessage()
                            + " Nenhuma sessão da série foi cancelada.");
                }
            }
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
