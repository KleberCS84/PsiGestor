package br.com.kleber.psigestor.service;

import br.com.kleber.psigestor.model.Atendimento;
import br.com.kleber.psigestor.model.Modalidade;
import br.com.kleber.psigestor.model.Paciente;
import br.com.kleber.psigestor.model.StatusAtendimento;
import br.com.kleber.psigestor.model.TipoConsentimento;
import br.com.kleber.psigestor.model.Usuario;
import br.com.kleber.psigestor.util.RegraNegocioException;
import br.com.kleber.psigestor.util.Validadores;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lembretes de sessao:
 *  - aviso a psicologa X min antes de cada sessao dela (controle de "ja avisado" so em memoria);
 *  - lembrete ao paciente das sessoes das proximas 24h, enviado pelo link wa.me (a psicologa confirma no WhatsApp).
 * Sigilo: o texto padrao so leva paciente, dia, hora, modalidade e profissional (nunca servico, observacao ou
 * dado clinico); exige CONTATO_WHATSAPP_EMAIL; a auditoria guarda so o id do atendimento e o canal.
 */
public class LembreteSessaoService {
    public static final String MODELO_PADRAO = "Olá, {paciente}! Passando para lembrar da sua sessão {quando} "
            + "às {hora} ({modalidade}) com {profissional}. Se precisar remarcar, é só me avisar por aqui.";
    public static final int MODELO_MAXIMO = 1000;
    /** Janela do painel de lembretes. */
    static final Duration JANELA_LEMBRETE = Duration.ofHours(24);
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");

    /** Lembrete pronto: o atendimento relido do banco, o paciente dele e a mensagem montada. */
    public record Lembrete(Atendimento atendimento, Paciente paciente, String mensagem) { }

    /** Aviso a psicologa: texto pronto para a tela. */
    public record AvisoSessao(int atendimentoId, long minutos, String texto) { }

    private final Contexto ctx;
    /** atendimento -> inicio ja avisado; remarcada (inicio diferente) avisa de novo. Zerado a cada login. */
    private final Map<Integer, LocalDateTime> avisados = new HashMap<>();

    LembreteSessaoService(Contexto ctx) { this.ctx = ctx; }

    /** Login novo (ou falha dele): o que foi avisado na sessao anterior nao vale mais. */
    void reiniciarAvisos() { avisados.clear(); }

    private LocalDateTime agora() { return ctx.agoraLocal().truncatedTo(ChronoUnit.MINUTES); }

    // ---------- lembrete ao paciente ----------

    /** Sessoes agendadas/confirmadas em (agora, agora + 24h] ainda nao lembradas, qualquer profissional. */
    public List<Atendimento> pendentes() {
        ctx.exigirSessao();
        LocalDateTime agora = agora();
        return ctx.atendimentos.listarParaLembrete(agora, agora.plus(JANELA_LEMBRETE));
    }

    public int contarPendentes() { return pendentes().size(); }

    public boolean temConsentimento(int pacienteId) {
        return ctx.pacienteService.temConsentimento(pacienteId, TipoConsentimento.CONTATO_WHATSAPP_EMAIL);
    }

    /** Relido do banco: a lista da tela nao entra na mensagem. */
    public Lembrete preparar(int atendimentoId) {
        Usuario usuario = ctx.usuarioLogado();
        Atendimento a = exigirPendente(atendimentoId);
        Paciente p = ctx.pacienteService.buscar(a.getPacienteId());
        String modelo = usuario.getModeloLembrete() == null ? MODELO_PADRAO : usuario.getModeloLembrete();
        return new Lembrete(a, p, montarMensagem(modelo, p, a, ctx.agoraLocal()));
    }

    private Atendimento exigirPendente(int atendimentoId) {
        ctx.exigirSessao();
        Atendimento a = ctx.atendimentos.buscar(atendimentoId)
                .orElseThrow(() -> new RegraNegocioException("Atendimento não encontrado."));
        if (a.getStatus() != StatusAtendimento.AGENDADO && a.getStatus() != StatusAtendimento.CONFIRMADO)
            throw new RegraNegocioException("Só sessões agendadas ou confirmadas recebem lembrete.");
        if (!a.getInicio().isAfter(ctx.agoraLocal()))
            throw new RegraNegocioException("A sessão já começou; não há o que lembrar.");
        if (a.getLembreteEnviadoEm() != null)
            throw new RegraNegocioException("O paciente já foi lembrado desta sessão.");
        return a;
    }

    /** Abre o WhatsApp com a mensagem pronta e marca a sessao como lembrada. Sem consentimento nada e gravado. */
    public URI enviarWhatsApp(int atendimentoId) {
        Lembrete l = preparar(atendimentoId);
        if (!temConsentimento(l.paciente().getId()))
            throw new RegraNegocioException("O paciente não autorizou contato por WhatsApp e e-mail. "
                    + "Registre o consentimento antes de enviar o lembrete.");
        if (!Validadores.telefoneValido(l.paciente().getTelefone()))
            throw new RegraNegocioException("Paciente sem telefone válido para WhatsApp (DDD + número). "
                    + "Corrija em Pacientes > Editar.");
        URI uri = EnvioAgendaService.linkWhatsApp(l.paciente().getTelefone(), l.mensagem());
        ctx.transacao(() -> {
            ctx.atendimentos.marcarLembrete(atendimentoId, instante());
            // so id e canal: nome, horario e texto nao vao para a trilha
            ctx.auditar("LEMBRETE_ENVIADO", "atendimento", atendimentoId, "canal: whatsapp");
        });
        return uri;
    }

    /** Lembrou por outro meio (ligacao, pessoalmente): marca sem enviar. Nao exige consentimento (nada e enviado). */
    public void marcarComoLembrado(int atendimentoId) {
        exigirPendente(atendimentoId);
        ctx.transacao(() -> {
            ctx.atendimentos.marcarLembrete(atendimentoId, instante());
            ctx.auditar("LEMBRETE_MARCADO", "atendimento", atendimentoId, "canal: manual");
        });
    }

    private String instante() { return ctx.agoraLocal().truncatedTo(ChronoUnit.SECONDS).toString(); }

    /**
     * Uma passada so (um nome com "{hora}" dentro nao e substituido de novo).
     * {quando}: "hoje" se a sessao e no dia de agora, senao "amanha" (a janela e de 24h).
     */
    static String montarMensagem(String modelo, Paciente p, Atendimento a, LocalDateTime agora) {
        String quando = a.getInicio().toLocalDate().equals(agora.toLocalDate()) ? "hoje" : "amanhã";
        String modalidade = a.getModalidade() == Modalidade.ONLINE ? "online" : "presencial";
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < modelo.length()) {
            if (modelo.startsWith("{paciente}", i)) { sb.append(p.getNome()); i += "{paciente}".length(); }
            else if (modelo.startsWith("{quando}", i)) { sb.append(quando); i += "{quando}".length(); }
            else if (modelo.startsWith("{hora}", i)) { sb.append(a.getInicio().format(HORA)); i += "{hora}".length(); }
            else if (modelo.startsWith("{modalidade}", i)) { sb.append(modalidade); i += "{modalidade}".length(); }
            else if (modelo.startsWith("{profissional}", i)) { sb.append(a.getProfissionalNome()); i += "{profissional}".length(); }
            else sb.append(modelo.charAt(i++));
        }
        return sb.toString();
    }

    // ---------- aviso a psicologa ----------

    /** A sessao comeca depois de agora e em no maximo "minutos" min: ja e hora de avisar. */
    static boolean deveAvisar(LocalDateTime inicio, LocalDateTime agora, int minutos) {
        return inicio.isAfter(agora) && !inicio.isAfter(agora.plusMinutes(minutos));
    }

    /** Minutos ate a sessao, arredondando para cima (faltam 30 s = "1 min"). */
    static long minutosRestantes(LocalDateTime inicio, LocalDateTime agora) {
        return (Duration.between(agora, inicio).toSeconds() + 59) / 60;
    }

    static String textoAviso(Atendimento a, long minutos) {
        return "Em " + minutos + " min: sessão com " + a.getPacienteNome() + " às " + a.getInicio().format(HORA)
                + " (" + (a.getModalidade() == Modalidade.ONLINE ? "online" : "presencial") + ")";
    }

    /**
     * Avisos devidos agora ao usuario logado (so as sessoes dele, agendadas/confirmadas). Cada sessao sai uma vez:
     * a coleta marca no controle em memoria. Nao registra atividade: o aviso nao mantem a sessao do app viva.
     */
    public List<AvisoSessao> coletarAvisos() {
        Usuario u = ctx.usuarioLogado();
        LocalDateTime agora = agora();
        int x = u.getMinutosAvisoSessao();
        // listarPeriodo e [de, ate): +1 min para incluir a sessao que comeca exatamente em agora + x
        List<Atendimento> proximas = ctx.atendimentos.listarPeriodo(agora, agora.plusMinutes(x + 1L), u.getId());
        List<AvisoSessao> avisos = new ArrayList<>();
        for (Atendimento a : proximas) {
            if (a.getStatus() != StatusAtendimento.AGENDADO && a.getStatus() != StatusAtendimento.CONFIRMADO) continue;
            if (!deveAvisar(a.getInicio(), agora, x)) continue;
            if (a.getInicio().equals(avisados.get(a.getId()))) continue;
            avisados.put(a.getId(), a.getInicio());
            long min = minutosRestantes(a.getInicio(), agora);
            avisos.add(new AvisoSessao(a.getId(), min, textoAviso(a, min)));
        }
        return avisos;
    }
}
