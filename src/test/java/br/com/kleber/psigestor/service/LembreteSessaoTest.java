package br.com.kleber.psigestor.service;

import br.com.kleber.psigestor.model.*;
import br.com.kleber.psigestor.service.AgendaService.EscopoSerie;
import br.com.kleber.psigestor.service.AgendaService.Frequencia;
import br.com.kleber.psigestor.service.AgendaService.ParametrosLote;
import br.com.kleber.psigestor.service.LembreteSessaoService.AvisoSessao;
import br.com.kleber.psigestor.util.Database;
import br.com.kleber.psigestor.util.RegraNegocioException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Lembretes de sessao: janela de 24h, consentimento, mensagem, aviso X min antes e preferencias. */
class LembreteSessaoTest {
    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    /** Segunda-feira, 12/10/2026 10:00. */
    private static final LocalDateTime AGORA = LocalDateTime.of(2026, 10, 12, 10, 0);
    private static final char[] SENHA_ANA = "senhaForte12".toCharArray();

    @TempDir Path tmp;
    Contexto ctx;
    Usuario ana;
    Servico servico;
    Paciente joao;
    Paciente maria;
    Atendimento hoje15;        // joao, presencial, dentro da janela
    Atendimento hoje16Cancelado;
    Atendimento hoje17Confirmado;
    Atendimento amanha09;      // joao, online
    Atendimento amanha10;      // maria, exatamente 24h depois de AGORA (borda: entra)
    Atendimento amanha11;      // 25h: fora
    Atendimento passada08;     // ja passou
    Atendimento agora10;       // comeca exatamente agora: nao entra

    @BeforeEach
    void preparar() {
        ctx = new Contexto("jdbc:sqlite:" + tmp.resolve("teste.db"));
        relogio(AGORA);
        ana = ctx.autenticacao.cadastrar("Ana Souza", "ana", "senhaForte12".toCharArray(), Papel.PSICOLOGO, "09/12345", 30);
        ctx.autenticacao.entrar("ana", "senhaForte12".toCharArray());
        servico = ctx.servicos.salvar(new Servico("Psicoterapia individual", null, 50, 20000));
        Paciente j = new Paciente("Joao Lima", LocalDate.of(1990, 5, 20), "(62) 99999-0000");
        j.setEmail("joao@exemplo.com");
        joao = ctx.pacienteService.salvar(j);
        maria = ctx.pacienteService.salvar(new Paciente("Maria Dias", null, "62988887777"));
        ctx.pacienteService.registrarConsentimento(joao.getId(), TipoConsentimento.ATENDIMENTO_ONLINE, true, "assinado");

        hoje15 = agendar(joao, LocalDateTime.of(2026, 10, 12, 15, 0), Modalidade.PRESENCIAL);
        hoje16Cancelado = agendar(maria, LocalDateTime.of(2026, 10, 12, 16, 0), Modalidade.PRESENCIAL);
        ctx.agenda.alterarStatus(hoje16Cancelado.getId(), StatusAtendimento.CANCELADO);
        hoje17Confirmado = agendar(maria, LocalDateTime.of(2026, 10, 12, 17, 0), Modalidade.PRESENCIAL);
        ctx.agenda.alterarStatus(hoje17Confirmado.getId(), StatusAtendimento.CONFIRMADO);
        amanha09 = agendar(joao, LocalDateTime.of(2026, 10, 13, 9, 0), Modalidade.ONLINE);
        amanha10 = agendar(maria, LocalDateTime.of(2026, 10, 13, 10, 0), Modalidade.PRESENCIAL);
        amanha11 = agendar(joao, LocalDateTime.of(2026, 10, 13, 11, 0), Modalidade.PRESENCIAL);
        passada08 = agendar(joao, LocalDateTime.of(2026, 10, 12, 8, 0), Modalidade.PRESENCIAL);
        agora10 = agendar(maria, LocalDateTime.of(2026, 10, 12, 10, 0), Modalidade.PRESENCIAL);
    }

    @AfterEach
    void fechar() { ctx.close(); }

    private void relogio(LocalDateTime t) {
        ctx.setRelogio(Clock.fixed(t.atZone(SAO_PAULO).toInstant(), SAO_PAULO));
    }

    /** Sessao nova no horario dado, com o aviso em x min (a inatividade de 15 min impede "andar" o relogio). */
    private void em(LocalDateTime t, int x) {
        ctx.sair();
        relogio(t);
        ctx.autenticacao.entrar("ana", SENHA_ANA.clone());
        ctx.autenticacao.alterarMinutosAviso(x);
    }

    private Atendimento agendar(Paciente p, LocalDateTime inicio, Modalidade m) {
        return ctx.agenda.agendar(p.getId(), ana.getId(), servico.getId(), inicio, m, false);
    }

    private void autorizarContato(Paciente p) {
        ctx.pacienteService.registrarConsentimento(p.getId(), TipoConsentimento.CONTATO_WHATSAPP_EMAIL, true, "teste");
    }

    private List<Integer> idsPendentes() {
        return ctx.lembretes.pendentes().stream().map(Atendimento::getId).toList();
    }

    private Atendimento reler(Atendimento a) { return ctx.atendimentos.buscar(a.getId()).orElseThrow(); }

    private boolean houveLog(String acao) {
        return ctx.logs.ultimos(100).stream().anyMatch(l -> l.acao().equals(acao));
    }

    // ---------- janela de 24h ----------
    @Test
    void janelaDe24hComBordaEStatus() {
        // dentro: 12/10 15:00, 12/10 17:00 (confirmado), 13/10 09:00 e 13/10 10:00 (exatamente 24h)
        assertEquals(List.of(hoje15.getId(), hoje17Confirmado.getId(), amanha09.getId(), amanha10.getId()),
                idsPendentes());
        assertEquals(4, ctx.lembretes.contarPendentes());
        // fora: 25h, passada, comeca agora; cancelado nunca entra
        List<Integer> ids = idsPendentes();
        for (Atendimento fora : List.of(amanha11, passada08, agora10, hoje16Cancelado))
            assertFalse(ids.contains(fora.getId()), "nao deveria listar " + fora.getInicio());
        // a janela anda com o relogio: um minuto depois, 13/10 11:00 (agora a 24h59) ainda esta fora
        relogio(AGORA.plusMinutes(1));
        assertEquals(List.of(hoje15.getId(), hoje17Confirmado.getId(), amanha09.getId(), amanha10.getId()),
                idsPendentes());
        // 10 minutos depois (dentro da inatividade de 15 min) ainda faltam 50 min para 13/10 11:00 entrar
        relogio(AGORA.plusMinutes(10));
        ctx.registrarAtividade();
        assertFalse(idsPendentes().contains(amanha11.getId()));
    }

    @Test
    void sessaoJaLembradaNaoAparece() {
        autorizarContato(joao);
        ctx.lembretes.enviarWhatsApp(hoje15.getId());
        ctx.lembretes.marcarComoLembrado(hoje17Confirmado.getId());
        assertEquals(List.of(amanha09.getId(), amanha10.getId()), idsPendentes());
        assertNotNull(reler(hoje15).getLembreteEnviadoEm());
        // segunda vez: recusado, nao duplica o log
        var ex = assertThrows(RegraNegocioException.class, () -> ctx.lembretes.enviarWhatsApp(hoje15.getId()));
        assertTrue(ex.getMessage().contains("já foi lembrado"), ex.getMessage());
        assertThrows(RegraNegocioException.class, () -> ctx.lembretes.marcarComoLembrado(hoje17Confirmado.getId()));
    }

    // ---------- consentimento e envio ----------
    @Test
    void semConsentimentoBloqueiaEnvioENadaEGravado() {
        assertFalse(ctx.lembretes.temConsentimento(joao.getId()));
        var ex = assertThrows(RegraNegocioException.class, () -> ctx.lembretes.enviarWhatsApp(hoje15.getId()));
        assertTrue(ex.getMessage().contains("não autorizou contato"), ex.getMessage());
        assertNull(reler(hoje15).getLembreteEnviadoEm());
        assertTrue(idsPendentes().contains(hoje15.getId()));
        assertFalse(houveLog("LEMBRETE_ENVIADO"));
        // consentimento revogado (novo registro) volta a bloquear
        autorizarContato(joao);
        ctx.pacienteService.registrarConsentimento(joao.getId(), TipoConsentimento.CONTATO_WHATSAPP_EMAIL, false, "revogou");
        assertThrows(RegraNegocioException.class, () -> ctx.lembretes.enviarWhatsApp(hoje15.getId()));
        assertNull(reler(hoje15).getLembreteEnviadoEm());
    }

    @Test
    void comConsentimentoAbreWhatsAppMarcaELogaSoIdECanal() {
        autorizarContato(joao);
        URI uri = ctx.lembretes.enviarWhatsApp(hoje15.getId());
        assertTrue(uri.toString().startsWith("https://wa.me/5562999990000?text="), uri.toString());
        assertTrue(uri.toString().contains("Joao%20Lima"), uri.toString());
        assertEquals(AGORA, reler(hoje15).getLembreteEnviadoEm());

        var log = ctx.logs.ultimos(1).get(0);
        assertEquals("LEMBRETE_ENVIADO", log.acao());
        assertEquals("atendimento", log.entidade());
        assertEquals(hoje15.getId(), log.entidadeId());
        assertEquals("canal: whatsapp", log.detalhe());
    }

    @Test
    void pacienteSemTelefoneValidoNaoMarca() {
        Paciente sem = ctx.pacienteService.salvar(new Paciente("Sem Fone", null, null));
        autorizarContato(sem);
        Atendimento a = agendar(sem, LocalDateTime.of(2026, 10, 12, 20, 0), Modalidade.PRESENCIAL);
        assertThrows(RegraNegocioException.class, () -> ctx.lembretes.enviarWhatsApp(a.getId()));
        assertNull(reler(a).getLembreteEnviadoEm());
    }

    @Test
    void marcarComoLembradoNaoEnviaELogaAcaoPropria() {
        ctx.lembretes.marcarComoLembrado(hoje15.getId());   // sem consentimento: nada e enviado, entao pode
        var log = ctx.logs.ultimos(1).get(0);
        assertEquals("LEMBRETE_MARCADO", log.acao());
        assertEquals("canal: manual", log.detalhe());
        assertFalse(houveLog("LEMBRETE_ENVIADO"));
        assertNotNull(reler(hoje15).getLembreteEnviadoEm());
    }

    @Test
    void sessaoQueNaoEPendenteNaoRecebeLembrete() {
        assertThrows(RegraNegocioException.class, () -> ctx.lembretes.marcarComoLembrado(hoje16Cancelado.getId()));
        assertThrows(RegraNegocioException.class, () -> ctx.lembretes.marcarComoLembrado(passada08.getId()));
        assertThrows(RegraNegocioException.class, () -> ctx.lembretes.marcarComoLembrado(999_999));
    }

    // ---------- mensagem ----------
    @Test
    void mensagemPadraoHojeEAmanha() {
        assertEquals("Olá, Joao Lima! Passando para lembrar da sua sessão hoje às 15:00 (presencial) com Ana Souza. "
                + "Se precisar remarcar, é só me avisar por aqui.", ctx.lembretes.preparar(hoje15.getId()).mensagem());
        assertEquals("Olá, Joao Lima! Passando para lembrar da sua sessão amanhã às 09:00 (online) com Ana Souza. "
                + "Se precisar remarcar, é só me avisar por aqui.", ctx.lembretes.preparar(amanha09.getId()).mensagem());
    }

    @Test
    void quandoVirandoODiaEmAmanhaEHoje() {
        Paciente p = new Paciente("Joao Lima", null, null);
        Atendimento a = new Atendimento();
        a.setInicio(LocalDateTime.of(2026, 10, 13, 8, 0));
        a.setModalidade(Modalidade.ONLINE);
        a.setProfissionalNome("Ana Souza");
        String modelo = "{quando} {hora} {modalidade}";
        // 23:00 do dia 12: a sessao das 08:00 e de amanha
        assertEquals("amanhã 08:00 online",
                LembreteSessaoService.montarMensagem(modelo, p, a, LocalDateTime.of(2026, 10, 12, 23, 0)));
        // 00:30 do dia 13: a mesma sessao e de hoje
        assertEquals("hoje 08:00 online",
                LembreteSessaoService.montarMensagem(modelo, p, a, LocalDateTime.of(2026, 10, 13, 0, 30)));
    }

    @Test
    void modeloPersonalizadoDoUsuarioEUmaPassadaSo() {
        ctx.autenticacao.alterarModeloLembrete("Oi {paciente}, {quando} {hora} ({modalidade}) com {profissional}");
        assertEquals("Oi Joao Lima, hoje 15:00 (presencial) com Ana Souza",
                ctx.lembretes.preparar(hoje15.getId()).mensagem());
        // nome com marcador dentro nao e substituido de novo
        Paciente estranho = new Paciente("{hora}", null, null);
        assertEquals("{hora} hoje", LembreteSessaoService.montarMensagem("{paciente} {quando}", estranho, hoje15, AGORA));
    }

    @Test
    void textoPadraoNaoTrazServicoObservacaoNemDadoClinico() {
        Atendimento a = ctx.agenda.agendar(joao.getId(), ana.getId(), servico.getId(),
                LocalDateTime.of(2026, 10, 12, 18, 0), null, Modalidade.PRESENCIAL, false, "queixa de ansiedade");
        String msg = ctx.lembretes.preparar(a.getId()).mensagem();
        assertFalse(msg.contains("ansiedade"), msg);
        assertFalse(msg.contains("Psicoterapia"), msg);
        assertFalse(msg.contains("20000") || msg.contains("200,00"), msg);
        // o padrao nem tem marcador para isso
        for (String marcador : List.of("{servico}", "{observacao}", "{motivo}"))
            assertFalse(LembreteSessaoService.MODELO_PADRAO.contains(marcador));
    }

    // ---------- aviso a psicologa ----------
    @Test
    void calculoDoAvisoXMinAntes() {
        LocalDateTime inicio = LocalDateTime.of(2026, 10, 12, 15, 0);
        for (int x : List.of(15, 30, 60, 120)) {
            assertTrue(LembreteSessaoService.deveAvisar(inicio, inicio.minusMinutes(x), x), "borda " + x);
            assertTrue(LembreteSessaoService.deveAvisar(inicio, inicio.minusMinutes(x - 1L), x), "dentro " + x);
            assertFalse(LembreteSessaoService.deveAvisar(inicio, inicio.minusMinutes(x + 1L), x), "cedo " + x);
        }
        assertFalse(LembreteSessaoService.deveAvisar(inicio, inicio, 60), "comecando agora");
        assertFalse(LembreteSessaoService.deveAvisar(inicio, inicio.plusMinutes(5), 60), "ja comecou");
        assertEquals(60, LembreteSessaoService.minutosRestantes(inicio, inicio.minusMinutes(60)));
        assertEquals(1, LembreteSessaoService.minutosRestantes(inicio, inicio.minusSeconds(30)));
        assertEquals(46, LembreteSessaoService.minutosRestantes(inicio, inicio.minusMinutes(45).minusSeconds(10)));
    }

    @Test
    void avisoComOTextoEAntecedenciaDaPreferencia() {
        assertEquals(60, ctx.usuarioLogado().getMinutosAvisoSessao());   // padrao
        em(LocalDateTime.of(2026, 10, 12, 13, 0), 60);
        assertTrue(ctx.lembretes.coletarAvisos().isEmpty(), "60 min: 15:00 ainda esta a 120 min");

        em(LocalDateTime.of(2026, 10, 12, 14, 0), 60);
        List<AvisoSessao> avisos = ctx.lembretes.coletarAvisos();
        assertEquals(1, avisos.size());
        assertEquals("Em 60 min: sessão com Joao Lima às 15:00 (presencial)", avisos.get(0).texto());

        em(LocalDateTime.of(2026, 10, 12, 13, 0), 120);
        assertEquals("Em 120 min: sessão com Joao Lima às 15:00 (presencial)", ctx.lembretes.coletarAvisos().get(0).texto());
    }

    @Test
    void avisoNaoRepeteERecomecaNoNovoLogin() {
        em(LocalDateTime.of(2026, 10, 12, 13, 0), 120);
        assertEquals(1, ctx.lembretes.coletarAvisos().size());
        assertTrue(ctx.lembretes.coletarAvisos().isEmpty(), "segunda coleta nao repete");
        assertTrue(ctx.lembretes.coletarAvisos().isEmpty());
        em(LocalDateTime.of(2026, 10, 12, 13, 0), 120);   // sair e entrar: o controle em memoria zera
        assertEquals(1, ctx.lembretes.coletarAvisos().size());
    }

    @Test
    void sessaoRemarcadaAvisaDeNovo() {
        em(LocalDateTime.of(2026, 10, 12, 13, 30), 120);
        assertEquals(1, ctx.lembretes.coletarAvisos().size());
        ctx.agenda.editar(hoje15.getId(), null, servico.getId(), LocalDateTime.of(2026, 10, 12, 15, 30), 50,
                Modalidade.PRESENCIAL, null);
        List<AvisoSessao> denovo = ctx.lembretes.coletarAvisos();
        assertEquals(1, denovo.size());
        assertEquals("Em 120 min: sessão com Joao Lima às 15:30 (presencial)", denovo.get(0).texto());
    }

    @Test
    void avisoSoDasSessoesDoUsuarioEAtivasQueAindaNaoComecaram() {
        Usuario bia = ctx.autenticacao.cadastrar("Bia Costa", "bia", "senhaBia1234".toCharArray(),
                Papel.PSICOLOGO, "06/54321", 0);
        ctx.agenda.agendar(maria.getId(), bia.getId(), servico.getId(), LocalDateTime.of(2026, 10, 12, 14, 0),
                Modalidade.PRESENCIAL, false);   // de outra profissional
        em(LocalDateTime.of(2026, 10, 12, 14, 30), 120);
        List<AvisoSessao> avisos = ctx.lembretes.coletarAvisos();
        // 15:00 (ana) sim; 14:00 (bia) nao e dela; 16:00 cancelada nao avisa
        assertEquals(List.of(hoje15.getId()), avisos.stream().map(AvisoSessao::atendimentoId).toList());

        em(LocalDateTime.of(2026, 10, 12, 15, 30), 120);   // 15:00 ja comecou; 17:00 confirmada avisa
        assertEquals(List.of(hoje17Confirmado.getId()),
                ctx.lembretes.coletarAvisos().stream().map(AvisoSessao::atendimentoId).toList());
    }

    // ---------- preferencias ----------
    @Test
    void preferenciasValidamEAuditamSemTexto() {
        assertThrows(RegraNegocioException.class, () -> ctx.autenticacao.alterarMinutosAviso(45));
        assertThrows(RegraNegocioException.class, () -> ctx.autenticacao.alterarMinutosAviso(0));
        ctx.autenticacao.alterarMinutosAviso(30);
        assertEquals(30, ctx.usuarios.buscar(ana.getId()).orElseThrow().getMinutosAvisoSessao());
        assertEquals("aviso de sessao: 60 -> 30", ctx.logs.ultimos(1).get(0).detalhe());

        assertThrows(RegraNegocioException.class,
                () -> ctx.autenticacao.alterarModeloLembrete("x".repeat(LembreteSessaoService.MODELO_MAXIMO + 1)));
        ctx.autenticacao.alterarModeloLembrete("Lembrete secreto de {paciente}");
        var log = ctx.logs.ultimos(1).get(0);
        assertEquals("PREFERENCIA_ALTERADA", log.acao());
        assertFalse(log.detalhe().contains("secreto"), log.detalhe());
        assertEquals("Lembrete secreto de {paciente}",
                ctx.usuarios.buscar(ana.getId()).orElseThrow().getModeloLembrete());
        ctx.autenticacao.alterarModeloLembrete("   ");   // vazio volta ao padrao
        assertNull(ctx.usuarios.buscar(ana.getId()).orElseThrow().getModeloLembrete());
        ctx.autenticacao.alterarModeloLembrete("x {paciente}");
        ctx.autenticacao.alterarModeloLembrete(LembreteSessaoService.MODELO_PADRAO);   // igual ao padrao = padrao
        assertNull(ctx.usuarios.buscar(ana.getId()).orElseThrow().getModeloLembrete());
    }

    // ---------- remarcar / trocar paciente zera o lembrete ----------
    @Test
    void remarcarOuTrocarPacienteZeraOLembrete() {
        ctx.lembretes.marcarComoLembrado(hoje15.getId());
        // editar sem mexer em horario nem paciente: a marca fica
        ctx.agenda.editar(hoje15.getId(), null, servico.getId(), hoje15.getInicio(), 50, Modalidade.PRESENCIAL, "so uma nota");
        assertNotNull(reler(hoje15).getLembreteEnviadoEm());

        // remarcar: volta a ser pendente
        ctx.agenda.editar(hoje15.getId(), null, servico.getId(), hoje15.getInicio().plusMinutes(30), 50,
                Modalidade.PRESENCIAL, null);
        assertNull(reler(hoje15).getLembreteEnviadoEm());
        assertTrue(idsPendentes().contains(hoje15.getId()));

        // trocar o paciente: o novo paciente precisa do lembrete
        ctx.lembretes.marcarComoLembrado(hoje15.getId());
        ctx.agenda.editar(hoje15.getId(), maria.getId(), servico.getId(), reler(hoje15).getInicio(), 50,
                Modalidade.PRESENCIAL, null);
        assertEquals(maria.getId(), reler(hoje15).getPacienteId());
        assertNull(reler(hoje15).getLembreteEnviadoEm());
    }

    @Test
    void remarcarSessaoDeSerieZeraSoAsAfetadas() {
        List<Atendimento> serie = ctx.agenda.agendarLote(
                new ParametrosLote(joao.getId(), ana.getId(), servico.getId(), null, Modalidade.PRESENCIAL, false, null),
                AgendaService.gerarDatas(LocalDateTime.of(2026, 10, 12, 19, 0), Frequencia.SEMANAL, 3));
        assertEquals(3, serie.size());
        String marca = "2026-10-12T09:00:00";
        for (Atendimento s : serie) ctx.atendimentos.marcarLembrete(s.getId(), marca);

        // esta e as proximas: a 2a e a 3a mudam de horario e voltam a pendentes; a 1a (anterior) fica
        Atendimento segunda = serie.get(1);
        ctx.agenda.editar(segunda.getId(), null, servico.getId(), segunda.getInicio().plusHours(1), 50,
                Modalidade.PRESENCIAL, null, EscopoSerie.ESTA_E_PROXIMAS);
        assertNotNull(reler(serie.get(0)).getLembreteEnviadoEm());
        assertNull(reler(serie.get(1)).getLembreteEnviadoEm());
        assertNull(reler(serie.get(2)).getLembreteEnviadoEm());

        // so esta: so a editada zera
        ctx.atendimentos.marcarLembrete(serie.get(2).getId(), marca);
        Atendimento primeira = serie.get(0);
        ctx.agenda.editar(primeira.getId(), null, servico.getId(), primeira.getInicio().plusHours(1), 50,
                Modalidade.PRESENCIAL, null, EscopoSerie.SO_ESTA);
        assertNull(reler(serie.get(0)).getLembreteEnviadoEm());
        assertNotNull(reler(serie.get(2)).getLembreteEnviadoEm());
    }

    // ---------- migracao ----------
    @Test
    void migracaoV12ParaV13CriaColunasEGuardaCopia() throws Exception {
        ctx.close();
        String url = "jdbc:sqlite:" + tmp.resolve("teste.db");
        try (var con = DriverManager.getConnection(url); var st = con.createStatement()) {
            // volta ao formato da v12 naquilo que o SQLite deixa remover (a coluna do aviso tem CHECK no schema novo)
            st.execute("ALTER TABLE atendimento DROP COLUMN lembrete_enviado_em");
            st.execute("ALTER TABLE usuario DROP COLUMN modelo_lembrete");
            st.execute("PRAGMA user_version = 12");
        }
        ctx = new Contexto(url);   // migracao v13
        relogio(AGORA);
        assertTrue(Files.exists(tmp.resolve("teste.db.pre-v13")), "copia de antes da migracao");
        assertEquals(13, Database.VERSAO_ESQUEMA);
        try (var con = DriverManager.getConnection(url); var st = con.createStatement()) {
            try (ResultSet rs = st.executeQuery("PRAGMA user_version")) { assertEquals(13, rs.getInt(1)); }
            assertTrue(temColuna(st, "atendimento", "lembrete_enviado_em"));
            assertTrue(temColuna(st, "usuario", "modelo_lembrete"));
            assertTrue(temColuna(st, "usuario", "minutos_aviso_sessao"));
        }
        ctx.autenticacao.entrar("ana", SENHA_ANA.clone());
        assertEquals(60, ctx.usuarioLogado().getMinutosAvisoSessao());   // existentes ficam no padrao
        assertNull(ctx.usuarioLogado().getModeloLembrete());
        assertEquals(4, idsPendentes().size());                           // atendimentos antigos: nao lembrados
    }

    private static boolean temColuna(java.sql.Statement st, String tabela, String coluna) throws Exception {
        try (ResultSet rs = st.executeQuery("PRAGMA table_info(" + tabela + ")")) {
            while (rs.next()) if (rs.getString("name").equalsIgnoreCase(coluna)) return true;
        }
        return false;
    }
}
