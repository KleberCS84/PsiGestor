package br.com.kleber.psigestor.service;

import br.com.kleber.psigestor.model.*;
import br.com.kleber.psigestor.service.EnvioAgendaService.EnvioAgenda;
import br.com.kleber.psigestor.service.EnvioAgendaService.PeriodoConsulta;
import br.com.kleber.psigestor.util.RegraNegocioException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Consultar agenda e envio de horarios ao paciente: filtro, mensagem, sigilo, consentimento, links e .ics. */
class EnvioAgendaTest {
    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final LocalDate HOJE = LocalDate.of(2026, 10, 12);   // segunda-feira
    private static final LocalDate ATE_30 = HOJE.plusDays(30);

    @TempDir Path tmp;
    Contexto ctx;
    Usuario ana;
    Servico sessao;
    Paciente joao;
    Paciente maria;
    Atendimento joao13;
    Atendimento joao20;

    @BeforeEach
    void preparar() {
        ctx = new Contexto("jdbc:sqlite:" + tmp.resolve("teste.db"));
        ctx.setRelogio(Clock.fixed(HOJE.atStartOfDay(SAO_PAULO).toInstant(), SAO_PAULO));
        ana = ctx.autenticacao.cadastrar("Ana Souza", "ana", "senhaForte12".toCharArray(), Papel.PSICOLOGO, "09/12345", 30);
        ctx.autenticacao.entrar("ana", "senhaForte12".toCharArray());
        sessao = ctx.servicos.salvar(new Servico("Psicoterapia individual", null, 50, 20000));
        Paciente j = new Paciente("Joao Lima", LocalDate.of(1990, 5, 20), "(62) 99999-0000");
        j.setEmail("joao@exemplo.com");
        joao = ctx.pacienteService.salvar(j);
        maria = ctx.pacienteService.salvar(new Paciente("Maria Dias", null, "62988887777"));
        ctx.pacienteService.registrarConsentimento(joao.getId(), TipoConsentimento.ATENDIMENTO_ONLINE, true, "assinado");
        // fora de ordem de proposito: a consulta ordena por data e hora
        joao20 = agendar(joao, LocalDateTime.of(2026, 10, 20, 9, 0), Modalidade.ONLINE);
        joao13 = agendar(joao, LocalDateTime.of(2026, 10, 13, 14, 0), Modalidade.PRESENCIAL);
        agendar(maria, LocalDateTime.of(2026, 10, 13, 16, 0), Modalidade.PRESENCIAL);
        agendar(joao, LocalDateTime.of(2026, 11, 20, 10, 0), Modalidade.PRESENCIAL);       // fora dos 30 dias
        Atendimento cancelado = agendar(joao, LocalDateTime.of(2026, 10, 14, 8, 0), Modalidade.PRESENCIAL);
        ctx.agenda.alterarStatus(cancelado.getId(), StatusAtendimento.CANCELADO);
    }

    @AfterEach
    void fechar() { ctx.close(); }

    private Atendimento agendar(Paciente p, LocalDateTime inicio, Modalidade m) {
        return ctx.agenda.agendar(p.getId(), ana.getId(), sessao.getId(), inicio, m, false);
    }

    private void autorizarContato(Paciente p) {
        ctx.pacienteService.registrarConsentimento(p.getId(), TipoConsentimento.CONTATO_WHATSAPP_EMAIL, true, "teste");
    }

    private EnvioAgenda prepararJoao() {
        return ctx.envioAgenda.preparar(joao.getId(), HOJE, ATE_30, StatusAtendimento.AGENDADO);
    }

    // ---------- filtro ----------
    @Test
    void filtroPorPacienteEPeriodo() {
        List<Atendimento> doJoao = ctx.envioAgenda.consultar(joao.getId(), HOJE, ATE_30, StatusAtendimento.AGENDADO);
        assertEquals(List.of(joao13.getId(), joao20.getId()), doJoao.stream().map(Atendimento::getId).toList());

        List<Atendimento> todosStatus = ctx.envioAgenda.consultar(joao.getId(), HOJE, ATE_30, null);
        assertEquals(3, todosStatus.size());                                  // + o cancelado, em ordem
        assertEquals(StatusAtendimento.CANCELADO, todosStatus.get(1).getStatus());

        List<Atendimento> geral = ctx.envioAgenda.consultar(null, HOJE, ATE_30, StatusAtendimento.AGENDADO);
        assertEquals(3, geral.size());                                         // visao geral inclui Maria
        assertEquals(3, ctx.envioAgenda.consultar(joao.getId(), HOJE, LocalDate.of(2026, 11, 30),
                StatusAtendimento.AGENDADO).size());                           // periodo maior pega 20/11

        assertThrows(RegraNegocioException.class, () -> ctx.envioAgenda.consultar(null, ATE_30, HOJE, null));
        assertThrows(RegraNegocioException.class, () -> ctx.envioAgenda.consultar(null, HOJE, null, null));
        assertThrows(RegraNegocioException.class, () -> ctx.envioAgenda.consultar(null, HOJE, HOJE.plusYears(2), null));
    }

    @Test
    void atalhosDePeriodo() {
        assertArrayEquals(new LocalDate[] {HOJE, ATE_30}, PeriodoConsulta.PROXIMOS_30_DIAS.intervalo(HOJE));
        assertArrayEquals(new LocalDate[] {LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)},
                PeriodoConsulta.ESTE_MES.intervalo(HOJE));
        assertArrayEquals(new LocalDate[] {LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30)},
                PeriodoConsulta.PROXIMO_MES.intervalo(HOJE));
        assertArrayEquals(new LocalDate[] {LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 31)},
                PeriodoConsulta.PROXIMO_MES.intervalo(LocalDate.of(2026, 12, 15)));
        assertNull(PeriodoConsulta.PERSONALIZADO.intervalo(HOJE));
    }

    // ---------- mensagem ----------
    @Test
    void mensagemComListaNoFormatoCerto() {
        assertEquals("""
                Olá, Joao Lima! Seguem seus próximos horários com Ana Souza:
                • ter, 13/10 às 14:00 (presencial)
                • ter, 20/10 às 09:00 (online)
                Se precisar remarcar, é só me avisar por aqui.""", prepararJoao().mensagem());

        ctx.autenticacao.alterarModeloMensagem("Oi {paciente}, seus horários:\n{lista}\n({profissional})");
        assertEquals("Oi Joao Lima, seus horários:\n• ter, 13/10 às 14:00 (presencial)\n"
                + "• ter, 20/10 às 09:00 (online)\n(Ana Souza)", prepararJoao().mensagem());
    }

    @Test
    void mensagemNaoTemOutroPaciente() {
        EnvioAgenda e = prepararJoao();
        assertTrue(e.atendimentos().stream().allMatch(a -> a.getPacienteId() == joao.getId().intValue()));
        assertFalse(e.mensagem().contains("Maria"), e.mensagem());
        assertFalse(e.mensagem().contains("16:00"), e.mensagem());          // horario da Maria no mesmo dia
        assertFalse(e.mensagem().contains("08:00"), e.mensagem());          // cancelado fora do filtro
        assertEquals(2, e.atendimentos().size());
        // nome com marcador nao e substituido de novo (uma passada so)
        Paciente esperto = new Paciente("{lista}", null, null);
        assertEquals("Olá, {lista}!", EnvioAgendaService.montarMensagem("Olá, {paciente}!", esperto, e.atendimentos()));
    }

    @Test
    void modeloSemListaERecusado() {
        assertThrows(RegraNegocioException.class, () -> ctx.autenticacao.alterarModeloMensagem("Oi {paciente}"));
        assertThrows(RegraNegocioException.class,
                () -> ctx.autenticacao.alterarModeloMensagem("{lista}" + "x".repeat(EnvioAgendaService.MODELO_MAXIMO)));
        ctx.autenticacao.alterarModeloMensagem("Horários: {lista}");
        var log = ctx.logs.ultimos(1).get(0);
        assertEquals("PREFERENCIA_ALTERADA", log.acao());
        assertFalse(log.detalhe().contains("Horários"), log.detalhe());       // texto do modelo fora da trilha
        assertEquals("Horários: {lista}", ctx.usuarios.buscar(ana.getId()).orElseThrow().getModeloMensagemAgenda());
        ctx.autenticacao.alterarModeloMensagem("  ");                          // vazio volta ao padrao
        assertNull(ctx.usuarios.buscar(ana.getId()).orElseThrow().getModeloMensagemAgenda());
        assertTrue(prepararJoao().mensagem().startsWith("Olá, Joao Lima!"));
    }

    // ---------- consentimento e contato ----------
    @Test
    void semConsentimentoEnvioBloqueadoAteRegistrar() {
        EnvioAgenda e = prepararJoao();
        String t = e.mensagem();
        assertThrows(RegraNegocioException.class, () -> ctx.envioAgenda.enviarWhatsApp(e, t));
        assertThrows(RegraNegocioException.class, () -> ctx.envioAgenda.enviarEmail(e, t));
        assertThrows(RegraNegocioException.class, () -> ctx.envioAgenda.copiar(e, t));
        assertThrows(RegraNegocioException.class, () -> ctx.envioAgenda.salvarIcs(e, tmp.resolve("ics")));
        assertFalse(Files.exists(tmp.resolve("ics")));
        assertFalse(ctx.logs.ultimos(20).stream().anyMatch(l -> l.acao().equals("ENVIO_AGENDA_PACIENTE")));

        autorizarContato(joao);
        assertDoesNotThrow(() -> ctx.envioAgenda.enviarWhatsApp(e, t));
        assertDoesNotThrow(() -> ctx.envioAgenda.enviarEmail(e, t));
        assertDoesNotThrow(() -> ctx.envioAgenda.copiar(e, t));

        ctx.pacienteService.registrarConsentimento(joao.getId(), TipoConsentimento.CONTATO_WHATSAPP_EMAIL, false, "revogou");
        assertThrows(RegraNegocioException.class, () -> ctx.envioAgenda.enviarWhatsApp(e, t));
    }

    @Test
    void telefoneOuEmailInvalidoDaMensagemClara() {
        Paciente pedro = ctx.pacienteService.salvar(new Paciente("Pedro Alves", null, null));
        agendar(pedro, LocalDateTime.of(2026, 10, 15, 10, 0), Modalidade.PRESENCIAL);
        autorizarContato(pedro);
        EnvioAgenda e = ctx.envioAgenda.preparar(pedro.getId(), HOJE, ATE_30, StatusAtendimento.AGENDADO);
        var zap = assertThrows(RegraNegocioException.class, () -> ctx.envioAgenda.enviarWhatsApp(e, e.mensagem()));
        assertTrue(zap.getMessage().contains("telefone") && zap.getMessage().contains("Pacientes > Editar"), zap.getMessage());
        var mail = assertThrows(RegraNegocioException.class, () -> ctx.envioAgenda.enviarEmail(e, e.mensagem()));
        assertTrue(mail.getMessage().contains("e-mail") && mail.getMessage().contains("Pacientes > Editar"), mail.getMessage());
        assertDoesNotThrow(() -> ctx.envioAgenda.copiar(e, e.mensagem()));    // copiar nao depende de contato
    }

    // ---------- formatos ----------
    @Test
    void linkDoWhatsAppComTelefoneETextoCodificados() {
        assertEquals("https://wa.me/5562999990000?text=Ol%C3%A1%2C%20tudo%20bem%3F%0A%E2%80%A2%20ter%2C%2013%2F10",
                EnvioAgendaService.linkWhatsApp("(62) 99999-0000", "Olá, tudo bem?\n• ter, 13/10").toString());
        // ja com o codigo do pais: nao vira 5555...
        assertTrue(EnvioAgendaService.linkWhatsApp("+55 62 99999-0000", "x").toString()
                .startsWith("https://wa.me/5562999990000?text="));
        assertEquals("mailto:joao@exemplo.com?subject=Seus%20hor%C3%A1rios&body=a%20b%26c",
                EnvioAgendaService.linkEmail("joao@exemplo.com", "a b&c").toString());

        autorizarContato(joao);
        EnvioAgenda e = prepararJoao();
        String link = ctx.envioAgenda.enviarWhatsApp(e, e.mensagem()).toString();
        assertTrue(link.startsWith("https://wa.me/5562999990000?text=Ol%C3%A1%2C%20Joao%20Lima"), link);
        assertFalse(link.contains(" ") || link.contains("+"), link);
    }

    @Test
    void icsValido() throws Exception {
        autorizarContato(joao);
        EnvioAgenda e = prepararJoao();
        String ics = EnvioAgendaService.gerarIcs(e.atendimentos(), SAO_PAULO, Instant.parse("2026-10-12T03:00:00Z"));

        assertTrue(ics.startsWith("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n"), ics);
        assertTrue(ics.endsWith("END:VCALENDAR\r\n"));
        assertFalse(ics.replace("\r\n", "").contains("\n"), "quebra de linha sem CR");
        for (String l : ics.split("\r\n"))
            assertTrue(l.getBytes(StandardCharsets.UTF_8).length <= 75, "linha com mais de 75 octetos: " + l);
        assertEquals(2, ics.split("BEGIN:VEVENT", -1).length - 1);
        assertEquals(2, ics.split("END:VEVENT", -1).length - 1);
        assertTrue(ics.contains("DTSTART:20261013T170000Z\r\n"), ics);           // 14:00 em Sao Paulo (UTC-3)
        assertTrue(ics.contains("DTEND:20261013T175000Z\r\n"), ics);
        assertTrue(ics.contains("SUMMARY:Sessão com Ana Souza\r\n"), ics);
        assertTrue(ics.contains("DTSTAMP:20261012T030000Z\r\n"), ics);
        // nada de paciente nem servico ("20000" nao serve de teste: aparece em T120000Z)
        assertFalse(ics.contains("Joao") || ics.contains("Psicoterapia"), ics);

        // nome longo: dobra em 75 octetos e, desdobrado, volta ao original (virgula escapada)
        Atendimento longo = e.atendimentos().get(0);
        longo.setProfissionalNome("Maria Aparecida de Albuquerque Guimarães Vasconcelos e Silva Junqueira, CRP 09/1");
        String dobrado = EnvioAgendaService.gerarIcs(List.of(longo), SAO_PAULO, Instant.EPOCH);
        for (String l : dobrado.split("\r\n"))
            assertTrue(l.getBytes(StandardCharsets.UTF_8).length <= 75, "linha com mais de 75 octetos: " + l);
        assertTrue(dobrado.replace("\r\n ", "").contains("SUMMARY:Sessão com Maria Aparecida de Albuquerque "
                + "Guimarães Vasconcelos e Silva Junqueira\\, CRP 09/1\r\n"), dobrado);

        // arquivo: nome sem dado do paciente e nunca sobrescreve
        Path pasta = tmp.resolve("convites");
        Path a1 = ctx.envioAgenda.salvarIcs(prepararJoao(), pasta);
        Path a2 = ctx.envioAgenda.salvarIcs(prepararJoao(), pasta);
        assertNotEquals(a1, a2);
        try (Stream<Path> arquivos = Files.list(pasta)) { assertEquals(2, arquivos.count()); }
        assertTrue(a1.getFileName().toString().startsWith("horarios_"), a1.toString());
        assertTrue(a1.getFileName().toString().endsWith(".ics"), a1.toString());
        assertFalse(a1.getFileName().toString().contains("Joao"), a1.toString());
        assertTrue(Files.readString(a1).startsWith("BEGIN:VCALENDAR"));
    }

    // ---------- auditoria e perfis ----------
    @Test
    void envioAuditadoSemTexto() {
        autorizarContato(joao);
        EnvioAgenda e = prepararJoao();
        ctx.envioAgenda.enviarWhatsApp(e, e.mensagem());
        var log = ctx.logs.ultimos(1).get(0);
        assertEquals("ENVIO_AGENDA_PACIENTE", log.acao());
        assertEquals("paciente", log.entidade());
        assertEquals(joao.getId(), log.entidadeId());
        assertEquals("canal: whatsapp; atendimentos: 2", log.detalhe());
        ctx.envioAgenda.salvarIcs(e, tmp.resolve("ics"));
        assertEquals("canal: ics; atendimentos: 2", ctx.logs.ultimos(1).get(0).detalhe());
    }

    @Test
    void secretariaTambemEnvia() {
        ctx.autenticacao.cadastrar("Bia Recepcao", "bia", "senhaBia1234".toCharArray(), Papel.SECRETARIA, null, 0);
        autorizarContato(joao);
        ctx.sair();
        ctx.autenticacao.entrar("bia", "senhaBia1234".toCharArray());
        EnvioAgenda e = prepararJoao();
        assertDoesNotThrow(() -> ctx.envioAgenda.copiar(e, e.mensagem()));
        ctx.sair();
        assertThrows(RegraNegocioException.class, this::prepararJoao);        // sem sessao, nada
    }

    @Test
    void periodoSemAtendimentosNaoMontaMensagem() {
        var ex = assertThrows(RegraNegocioException.class, () -> ctx.envioAgenda.preparar(
                maria.getId(), LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31), StatusAtendimento.AGENDADO));
        assertTrue(ex.getMessage().contains("Nenhum atendimento"), ex.getMessage());
    }
}
