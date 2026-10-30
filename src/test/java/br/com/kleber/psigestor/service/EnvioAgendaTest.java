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
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Consultar agenda e envio de horarios ao paciente: filtro, mensagem, sigilo, consentimento, links e .ics. */
class EnvioAgendaTest {
    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final LocalDate HOJE = LocalDate.of(2026, 10, 12);   // segunda-feira
    private static final LocalDate ATE_30 = HOJE.plusDays(30);
    private static final Set<StatusAtendimento> SO_AGENDADO = EnumSet.of(StatusAtendimento.AGENDADO);
    private static final Set<StatusAtendimento> TODOS = EnumSet.allOf(StatusAtendimento.class);

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
        return ctx.envioAgenda.preparar(joao.getId(), HOJE, ATE_30, SO_AGENDADO);
    }

    // ---------- filtro ----------
    @Test
    void filtroPorPacienteEPeriodo() {
        List<Atendimento> doJoao = ctx.envioAgenda.consultar(joao.getId(), HOJE, ATE_30, SO_AGENDADO);
        assertEquals(List.of(joao13.getId(), joao20.getId()), doJoao.stream().map(Atendimento::getId).toList());

        List<Atendimento> todosStatus = ctx.envioAgenda.consultar(joao.getId(), HOJE, ATE_30, TODOS);
        assertEquals(3, todosStatus.size());                                  // + o cancelado, em ordem
        assertEquals(StatusAtendimento.CANCELADO, todosStatus.get(1).getStatus());

        List<Atendimento> geral = ctx.envioAgenda.consultar(null, HOJE, ATE_30, SO_AGENDADO);
        assertEquals(3, geral.size());                                         // visao geral inclui Maria
        assertEquals(3, ctx.envioAgenda.consultar(joao.getId(), HOJE, LocalDate.of(2026, 11, 30),
                SO_AGENDADO).size());                           // periodo maior pega 20/11

        assertThrows(RegraNegocioException.class, () -> ctx.envioAgenda.consultar(null, ATE_30, HOJE, TODOS));
        assertThrows(RegraNegocioException.class, () -> ctx.envioAgenda.consultar(null, HOJE, null, TODOS));
        assertThrows(RegraNegocioException.class, () -> ctx.envioAgenda.consultar(null, HOJE, HOJE.plusYears(2), TODOS));
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
    void mensagemComUmStatusNaoMostraStatus() {
        assertEquals("""
                Olá, Joao Lima! Seguem suas sessões de 12/10 a 11/11 com Ana Souza:
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
        assertEquals("Olá, {lista}!", EnvioAgendaService.montarMensagem("Olá, {paciente}!", esperto, e.atendimentos(),
                HOJE, ATE_30, false));
    }

    // ---------- varios status ----------
    @Test
    void filtroComDoisStatus() {
        var agendadoOuCancelado = EnumSet.of(StatusAtendimento.AGENDADO, StatusAtendimento.CANCELADO);
        List<Atendimento> lista = ctx.envioAgenda.consultar(joao.getId(), HOJE, ATE_30, agendadoOuCancelado);
        assertEquals(List.of(StatusAtendimento.AGENDADO, StatusAtendimento.CANCELADO, StatusAtendimento.AGENDADO),
                lista.stream().map(Atendimento::getStatus).toList());           // 13/10, 14/10 (cancelado), 20/10
        assertEquals(1, ctx.envioAgenda.consultar(joao.getId(), HOJE, ATE_30,
                EnumSet.of(StatusAtendimento.CANCELADO)).size());
        assertThrows(RegraNegocioException.class, () -> ctx.envioAgenda.consultar(joao.getId(), HOJE, ATE_30,
                EnumSet.noneOf(StatusAtendimento.class)));
        assertThrows(RegraNegocioException.class, () -> ctx.envioAgenda.consultar(joao.getId(), HOJE, ATE_30, null));
    }

    @Test
    void mensagemComDoisStatusMostraStatusEmCadaLinha() {
        EnvioAgenda e = ctx.envioAgenda.preparar(joao.getId(), HOJE, ATE_30,
                EnumSet.of(StatusAtendimento.AGENDADO, StatusAtendimento.CANCELADO));
        assertEquals("""
                Olá, Joao Lima! Seguem suas sessões de 12/10 a 11/11 com Ana Souza:
                • ter, 13/10 às 14:00 (presencial) - agendada
                • qua, 14/10 às 08:00 (presencial) - cancelada
                • ter, 20/10 às 09:00 (online) - agendada
                Se precisar remarcar, é só me avisar por aqui.""", e.mensagem());
        // status vem do que foi marcado: dois marcados, mesmo que so um apareca, as linhas dizem o status
        EnvioAgenda soAgendados = ctx.envioAgenda.preparar(joao.getId(), HOJE, ATE_30,
                EnumSet.of(StatusAtendimento.AGENDADO, StatusAtendimento.REALIZADO));
        assertTrue(soAgendados.mensagem().contains("• ter, 13/10 às 14:00 (presencial) - agendada"), soAgendados.mensagem());
    }

    @Test
    void rotulosParaPaciente() {
        assertEquals("agendada", StatusAtendimento.AGENDADO.rotuloParaPaciente());
        assertEquals("confirmada", StatusAtendimento.CONFIRMADO.rotuloParaPaciente());
        assertEquals("realizada", StatusAtendimento.REALIZADO.rotuloParaPaciente());
        assertEquals("não compareceu", StatusAtendimento.FALTOU.rotuloParaPaciente());
        assertEquals("cancelada", StatusAtendimento.CANCELADO.rotuloParaPaciente());
        assertEquals("FALTOU", StatusAtendimento.FALTOU.name());               // enum nao renomeado (banco)
        assertEquals("Faltou", StatusAtendimento.FALTOU.rotulo());             // rotulo de tela continua
    }

    @Test
    void periodoNaMensagem() {
        assertEquals("01/10 a 31/10", EnvioAgendaService.periodo(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)));
        assertEquals("15/12/2026 a 14/01/2027",
                EnvioAgendaService.periodo(LocalDate.of(2026, 12, 15), LocalDate.of(2027, 1, 14)));
    }

    @Test
    void icsSemCanceladosESemPassados() throws Exception {
        autorizarContato(joao);
        // passado e realizado (dentro dos 30 dias retroativos do agendamento)
        Atendimento passado = agendar(joao, LocalDateTime.of(2026, 10, 5, 10, 0), Modalidade.PRESENCIAL);
        ctx.agenda.alterarStatus(passado.getId(), StatusAtendimento.REALIZADO);
        EnvioAgenda e = ctx.envioAgenda.preparar(joao.getId(), LocalDate.of(2026, 10, 1), ATE_30, TODOS);
        assertEquals(4, e.atendimentos().size());   // 05/10 realizado, 13/10, 14/10 cancelado, 20/10

        var convite = ctx.envioAgenda.salvarIcs(e, tmp.resolve("ics"));
        assertEquals(2, convite.incluidos());
        assertEquals(2, convite.deFora());
        String ics = Files.readString(convite.arquivo());
        assertEquals(2, ics.split("BEGIN:VEVENT", -1).length - 1);
        assertTrue(ics.contains("DTSTART:20261013T170000Z"), ics);
        assertTrue(ics.contains("DTSTART:20261020T120000Z"), ics);
        assertFalse(ics.contains("DTSTART:20261014"), "cancelado entrou no convite");
        assertFalse(ics.contains("DTSTART:20261005"), "passado entrou no convite");
        assertEquals("canal: ics; atendimentos: 2", ctx.logs.ultimos(1).get(0).detalhe());

        // so passados/cancelados: nada para o convite
        EnvioAgenda semFuturo = ctx.envioAgenda.preparar(joao.getId(), HOJE, ATE_30,
                EnumSet.of(StatusAtendimento.CANCELADO));
        var ex = assertThrows(RegraNegocioException.class, () -> ctx.envioAgenda.salvarIcs(semFuturo, tmp.resolve("ics2")));
        assertTrue(ex.getMessage().contains("passados ou cancelados"), ex.getMessage());
        assertFalse(Files.exists(tmp.resolve("ics2")));

        LocalDateTime agora = ctx.envioAgenda.agora();
        assertTrue(EnvioAgendaService.entraNoConvite(joao13, agora));
        assertFalse(EnvioAgendaService.entraNoConvite(passado, agora));
    }

    @Test
    void modeloPadraoAntigoMigradoEPersonalizadoPreservado() throws Exception {
        Usuario bia = ctx.autenticacao.cadastrar("Bia Recepcao", "bia", "senhaBia1234".toCharArray(), Papel.SECRETARIA, null, 0);
        // como estava gravado antes da v11: o padrao antigo literal (Ana) e um modelo proprio (Bia)
        ctx.usuarios.atualizarModeloMensagem(ana.getId(), EnvioAgendaService.MODELO_PADRAO_ANTERIOR);
        ctx.usuarios.atualizarModeloMensagem(bia.getId(), "Oi {paciente}! {lista}");
        ctx.close();
        String url = "jdbc:sqlite:" + tmp.resolve("teste.db");
        try (var con = java.sql.DriverManager.getConnection(url); var st = con.createStatement()) {
            st.execute("PRAGMA user_version = 10");
        }
        ctx = new Contexto(url);   // migracao v11
        ctx.setRelogio(Clock.fixed(HOJE.atStartOfDay(SAO_PAULO).toInstant(), SAO_PAULO));
        assertNull(ctx.usuarios.buscar(ana.getId()).orElseThrow().getModeloMensagemAgenda());
        assertEquals("Oi {paciente}! {lista}", ctx.usuarios.buscar(bia.getId()).orElseThrow().getModeloMensagemAgenda());

        ctx.autenticacao.entrar("ana", "senhaForte12".toCharArray());
        assertTrue(prepararJoao().mensagem().startsWith("Olá, Joao Lima! Seguem suas sessões de 12/10 a 11/11"));
        // quem digita o padrao antigo de novo tambem fica no padrao (null), acompanhando o novo
        ctx.autenticacao.alterarModeloMensagem("Horários: {lista}");
        ctx.autenticacao.alterarModeloMensagem(EnvioAgendaService.MODELO_PADRAO_ANTERIOR);
        assertNull(ctx.usuarios.buscar(ana.getId()).orElseThrow().getModeloMensagemAgenda());
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
        EnvioAgenda e = ctx.envioAgenda.preparar(pedro.getId(), HOJE, ATE_30, SO_AGENDADO);
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
        Path a1 = ctx.envioAgenda.salvarIcs(prepararJoao(), pasta).arquivo();
        Path a2 = ctx.envioAgenda.salvarIcs(prepararJoao(), pasta).arquivo();
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
                maria.getId(), LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31), SO_AGENDADO));
        assertTrue(ex.getMessage().contains("Nenhum atendimento"), ex.getMessage());
    }
}
