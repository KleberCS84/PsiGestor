package br.com.kleber.psigestor.service;

import br.com.kleber.psigestor.model.*;
import br.com.kleber.psigestor.util.RegraNegocioException;
import br.com.kleber.psigestor.util.Validadores;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * "Consultar agenda": lista atendimentos por paciente/periodo/status e envia ao paciente os horarios dele
 * (WhatsApp, e-mail, copia ou convite .ics). Sigilo:
 *  - a mensagem so leva atendimentos do paciente escolhido, relidos do banco (nunca a lista da tela);
 *  - exige CONTATO_WHATSAPP_EMAIL vigente;
 *  - a auditoria guarda canal e quantidade, nunca o texto;
 *  - o .ics nao tem nome de paciente, servico, observacao nem valor.
 */
public class EnvioAgendaService {
    public static final String MODELO_PADRAO = """
            Olá, {paciente}! Seguem seus próximos horários com {profissional}:
            {lista}
            Se precisar remarcar, é só me avisar por aqui.""";
    public static final int MODELO_MAXIMO = 1000;
    static final int DIAS_MAXIMOS_CONSULTA = 366;
    private static final String[] DIAS = {"seg", "ter", "qua", "qui", "sex", "sáb", "dom"};
    private static final DateTimeFormatter DIA_MES = DateTimeFormatter.ofPattern("dd/MM");
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter ICS_UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final DateTimeFormatter CARIMBO = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /** Atalhos de periodo da tela; PERSONALIZADO usa as datas digitadas. */
    public enum PeriodoConsulta {
        PROXIMOS_30_DIAS("Próximos 30 dias"), ESTE_MES("Este mês"), PROXIMO_MES("Próximo mês"),
        PERSONALIZADO("Personalizado");

        private final String rotulo;
        PeriodoConsulta(String rotulo) { this.rotulo = rotulo; }
        public String rotulo() { return rotulo; }

        /** [de, ate] inclusive; PERSONALIZADO nao tem intervalo proprio (null). */
        public LocalDate[] intervalo(LocalDate hoje) {
            return switch (this) {
                case PROXIMOS_30_DIAS -> new LocalDate[] {hoje, hoje.plusDays(30)};
                case ESTE_MES -> new LocalDate[] {hoje.withDayOfMonth(1), hoje.withDayOfMonth(hoje.lengthOfMonth())};
                case PROXIMO_MES -> {
                    LocalDate m = hoje.plusMonths(1).withDayOfMonth(1);
                    yield new LocalDate[] {m, m.withDayOfMonth(m.lengthOfMonth())};
                }
                case PERSONALIZADO -> null;
            };
        }
    }

    public enum Canal {
        WHATSAPP("whatsapp"), EMAIL("email"), COPIA("copia"), ICS("ics");

        private final String log;
        Canal(String log) { this.log = log; }
    }

    /** O que vai ao paciente: so os atendimentos dele e a mensagem montada com o modelo do usuario. */
    public record EnvioAgenda(Paciente paciente, List<Atendimento> atendimentos, String mensagem) { }

    private final Contexto ctx;

    EnvioAgendaService(Contexto ctx) { this.ctx = ctx; }

    /** Hoje pelo relogio do contexto (atalhos de periodo da tela). */
    public LocalDate hoje() { return ctx.hoje(); }

    /** Visao geral (pacienteId null) ou de um paciente; status null = todos. Ordem por data e hora. */
    public List<Atendimento> consultar(Integer pacienteId, LocalDate de, LocalDate ateInclusive, StatusAtendimento status) {
        ctx.exigirSessao();
        validarPeriodo(de, ateInclusive);
        return ctx.atendimentos.consultar(pacienteId, de.atStartOfDay(), ateInclusive.plusDays(1).atStartOfDay(), status);
    }

    private static void validarPeriodo(LocalDate de, LocalDate ate) {
        if (de == null || ate == null) throw new RegraNegocioException("Informe as datas do período.");
        if (ate.isBefore(de)) throw new RegraNegocioException("A data final é anterior à inicial.");
        if (de.plusDays(DIAS_MAXIMOS_CONSULTA).isBefore(ate))
            throw new RegraNegocioException("Período muito longo: consulte no máximo 1 ano por vez.");
    }

    /** Relida do banco pelo paciente: a lista da tela nunca entra na mensagem (sigilo). */
    public EnvioAgenda preparar(int pacienteId, LocalDate de, LocalDate ateInclusive, StatusAtendimento status) {
        Usuario usuario = ctx.usuarioLogado();
        Paciente p = ctx.pacienteService.buscar(pacienteId);
        List<Atendimento> lista = consultar(pacienteId, de, ateInclusive, status).stream()
                .filter(a -> a.getPacienteId() == pacienteId)   // segunda defesa, alem do WHERE
                .toList();
        if (lista.isEmpty()) throw new RegraNegocioException("Nenhum atendimento no período para enviar.");
        String modelo = usuario.getModeloMensagemAgenda() == null ? MODELO_PADRAO : usuario.getModeloMensagemAgenda();
        return new EnvioAgenda(p, lista, montarMensagem(modelo, p, lista));
    }

    /** Uma passada so: um nome com "{lista}" dentro nao e substituido de novo. */
    static String montarMensagem(String modelo, Paciente p, List<Atendimento> lista) {
        List<String> profissionais = lista.stream().map(Atendimento::getProfissionalNome).distinct().toList();
        boolean variosProfissionais = profissionais.size() > 1;
        String linhas = lista.stream().map(a -> linha(a, variosProfissionais)).collect(Collectors.joining("\n"));
        String nomesProf = String.join(" e ", profissionais);
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < modelo.length()) {
            if (modelo.startsWith("{paciente}", i)) { sb.append(p.getNome()); i += "{paciente}".length(); }
            else if (modelo.startsWith("{profissional}", i)) { sb.append(nomesProf); i += "{profissional}".length(); }
            else if (modelo.startsWith("{lista}", i)) { sb.append(linhas); i += "{lista}".length(); }
            else sb.append(modelo.charAt(i++));
        }
        return sb.toString();
    }

    /** "• qua, 05/11 às 14:00 (online)"; dia da semana sem o ponto que o Locale poe ("qua."). */
    static String linha(Atendimento a, boolean comProfissional) {
        LocalDateTime i = a.getInicio();
        return "• " + diaDaSemana(i.toLocalDate()) + ", " + i.format(DIA_MES) + " às " + i.format(HORA)
                + " (" + (a.getModalidade() == Modalidade.ONLINE ? "online" : "presencial") + ")"
                + (comProfissional ? " com " + a.getProfissionalNome() : "");
    }

    /** Dia da semana abreviado (tela e mensagem usam o mesmo texto). */
    public static String diaDaSemana(LocalDate d) { return DIAS[d.getDayOfWeek().getValue() - 1]; }

    /** Duracao em minutos para a tabela. */
    public static long duracaoMin(Atendimento a) { return Duration.between(a.getInicio(), a.getFim()).toMinutes(); }

    // ---------- envio ----------

    public boolean temConsentimentoContato(int pacienteId) {
        return ctx.pacienteService.temConsentimento(pacienteId, TipoConsentimento.CONTATO_WHATSAPP_EMAIL);
    }

    /** Consentimento e contato do canal; a tela chama antes da previa para avisar cedo. */
    public void verificar(EnvioAgenda e, Canal canal) { exigirPodeEnviar(e, canal); }

    private void exigirPodeEnviar(EnvioAgenda e, Canal canal) {
        ctx.exigirSessao();
        if (!temConsentimentoContato(e.paciente().getId()))
            throw new RegraNegocioException("O paciente não autorizou contato por WhatsApp e e-mail. "
                    + "Registre o consentimento antes de enviar os horários.");
        if (canal == Canal.WHATSAPP && !Validadores.telefoneValido(e.paciente().getTelefone()))
            throw new RegraNegocioException("Paciente sem telefone válido para WhatsApp (DDD + número). "
                    + "Corrija em Pacientes > Editar.");
        if (canal == Canal.EMAIL && !Validadores.emailValido(e.paciente().getEmail()))
            throw new RegraNegocioException("Paciente sem e-mail válido. Corrija em Pacientes > Editar.");
    }

    private void auditar(EnvioAgenda e, Canal canal) {
        // canal e quantidade: o texto (nomes, horarios) nao vai para a trilha
        ctx.auditar("ENVIO_AGENDA_PACIENTE", "paciente", e.paciente().getId(),
                "canal: " + canal.log + "; atendimentos: " + e.atendimentos().size());
    }

    /** texto: a mensagem conferida (e talvez ajustada) na previa da tela. */
    public URI enviarWhatsApp(EnvioAgenda e, String texto) {
        exigirPodeEnviar(e, Canal.WHATSAPP);
        URI uri = linkWhatsApp(e.paciente().getTelefone(), texto);
        ctx.transacao(() -> auditar(e, Canal.WHATSAPP));
        return uri;
    }

    public URI enviarEmail(EnvioAgenda e, String texto) {
        exigirPodeEnviar(e, Canal.EMAIL);
        URI uri = linkEmail(e.paciente().getEmail().trim(), texto);
        ctx.transacao(() -> auditar(e, Canal.EMAIL));
        return uri;
    }

    /** Para outros canais: a tela poe o texto devolvido na area de transferencia. */
    public String copiar(EnvioAgenda e, String texto) {
        exigirPodeEnviar(e, Canal.COPIA);
        ctx.transacao(() -> auditar(e, Canal.COPIA));
        return texto;
    }

    /** Convite para anexar: nome sem dado do paciente e CREATE_NEW (nunca sobrescreve). */
    public Path salvarIcs(EnvioAgenda e, Path pasta) {
        exigirPodeEnviar(e, Canal.ICS);
        String ics = gerarIcs(e.atendimentos(), ctx.zona(), ctx.agora());
        Path arq = pasta.resolve("horarios_" + ctx.agoraLocal().format(CARIMBO) + "_"
                + BackupService.sufixoAleatorio() + ".ics");
        try {
            Files.createDirectories(pasta);
            // log e arquivo juntos: arquivo que nao foi gravado desfaz o log
            ctx.transacao(() -> {
                auditar(e, Canal.ICS);
                try {
                    Files.writeString(arq, ics, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
            return arq;
        } catch (IOException | UncheckedIOException ex) {
            // caminho e mensagem do sistema ficam fora da tela (podem expor a pasta do usuario)
            throw new RegraNegocioException("Não foi possível gravar o convite nessa pasta. Escolha outra.");
        }
    }

    // ---------- formatos ----------

    /** https://wa.me/55<digitos>?text=...; telefone ja com 55 (12/13 digitos) nao ganha outro. */
    static URI linkWhatsApp(String telefone, String texto) {
        String d = Validadores.somenteDigitos(telefone);
        if (!((d.length() == 12 || d.length() == 13) && d.startsWith("55"))) d = "55" + d;
        return URI.create("https://wa.me/" + d + "?text=" + codificar(texto));
    }

    static URI linkEmail(String email, String texto) {
        return URI.create("mailto:" + codificar(email).replace("%40", "@")
                + "?subject=" + codificar("Seus horários") + "&body=" + codificar(texto));
    }

    /** URLEncoder codifica para formulario (espaco = "+"); em link e mailto o espaco e %20. */
    static String codificar(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** iCalendar (RFC 5545): CRLF, linhas dobradas em 75 octetos, horarios em UTC. */
    static String gerarIcs(List<Atendimento> lista, ZoneId zona, Instant agora) {
        StringBuilder sb = new StringBuilder();
        linhaIcs(sb, "BEGIN:VCALENDAR");
        linhaIcs(sb, "VERSION:2.0");
        linhaIcs(sb, "PRODID:-//PsiGestor//Horarios//PT-BR");
        linhaIcs(sb, "CALSCALE:GREGORIAN");
        linhaIcs(sb, "METHOD:PUBLISH");
        String stamp = ICS_UTC.format(agora.atOffset(ZoneOffset.UTC));
        for (Atendimento a : lista) {
            linhaIcs(sb, "BEGIN:VEVENT");
            linhaIcs(sb, "UID:" + UUID.randomUUID() + "@psigestor");
            linhaIcs(sb, "DTSTAMP:" + stamp);
            linhaIcs(sb, "DTSTART:" + utc(a.getInicio(), zona));
            linhaIcs(sb, "DTEND:" + utc(a.getFim(), zona));
            // so o profissional: nada de paciente, servico, observacao ou valor
            linhaIcs(sb, "SUMMARY:" + escapar("Sessão com " + a.getProfissionalNome()));
            linhaIcs(sb, "LOCATION:" + escapar(a.getModalidade().rotulo()));
            linhaIcs(sb, "END:VEVENT");
        }
        linhaIcs(sb, "END:VCALENDAR");
        return sb.toString();
    }

    private static String utc(LocalDateTime t, ZoneId zona) {
        return ICS_UTC.format(t.atZone(zona).withZoneSameInstant(ZoneOffset.UTC));
    }

    static String escapar(String s) {
        return s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n");
    }

    /** Dobra em 75 octetos UTF-8 sem partir um caractere; a continuacao comeca com espaco. */
    private static void linhaIcs(StringBuilder sb, String linha) {
        int octetos = 0;
        for (int i = 0; i < linha.length(); ) {
            int cp = linha.codePointAt(i);
            int n = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (octetos + n > 75) {
                sb.append("\r\n ");
                octetos = 1;
            }
            sb.appendCodePoint(cp);
            octetos += n;
            i += Character.charCount(cp);
        }
        sb.append("\r\n");
    }
}
