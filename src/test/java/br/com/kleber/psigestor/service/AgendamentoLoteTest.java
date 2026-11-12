package br.com.kleber.psigestor.service;

import br.com.kleber.psigestor.model.*;
import br.com.kleber.psigestor.service.AgendaService.EscopoSerie;
import br.com.kleber.psigestor.service.AgendaService.Frequencia;
import br.com.kleber.psigestor.service.AgendaService.ItemPrevia;
import br.com.kleber.psigestor.service.AgendaService.ParametrosLote;
import br.com.kleber.psigestor.service.AgendaService.SituacaoPrevia;
import br.com.kleber.psigestor.util.Database;
import br.com.kleber.psigestor.util.RegraNegocioException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Agendamento em lote: geracao, previa com conflitos, tudo-ou-nada, escopo da serie e migracao v12. */
class AgendamentoLoteTest {
    @TempDir Path tmp;
    Contexto ctx;
    Usuario ana;
    Servico sessao;
    Paciente joao;
    Paciente maria;

    /** Terca 20/10/2026, 08:00 (hoje fixo = 12/10/2026). */
    static final LocalDateTime PRIMEIRA = LocalDate.of(2026, 10, 20).atTime(8, 0);

    @BeforeEach
    void preparar() {
        ctx = new Contexto(url());
        ZoneId zona = ZoneId.systemDefault();
        ctx.setRelogio(Clock.fixed(LocalDate.of(2026, 10, 12).atStartOfDay(zona).toInstant(), zona));
        ana = ctx.autenticacao.cadastrar("Ana Souza", "ana", "senhaForte12".toCharArray(),
                Papel.PSICOLOGO, "09/12345", 30);
        ctx.autenticacao.entrar("ana", "senhaForte12".toCharArray());
        sessao = ctx.servicos.salvar(new Servico("Psicoterapia individual", null, 50, 20000));
        joao = ctx.pacienteService.salvar(new Paciente("Joao Lima", LocalDate.of(1990, 5, 20), "62999990000"));
        maria = ctx.pacienteService.salvar(new Paciente("Maria Costa", LocalDate.of(1985, 3, 2), "62988880000"));
    }

    @AfterEach
    void fechar() { ctx.close(); }

    private String url() { return "jdbc:sqlite:" + tmp.resolve("teste.db"); }

    private ParametrosLote params() {
        return new ParametrosLote(joao.getId(), ana.getId(), sessao.getId(), null, Modalidade.PRESENCIAL, false, null);
    }

    private List<Atendimento> serieSemanal(int total) {
        return ctx.agenda.agendarLote(params(), AgendaService.gerarDatas(PRIMEIRA, Frequencia.SEMANAL, total));
    }

    private Atendimento recarregar(Atendimento a) { return ctx.atendimentos.buscar(a.getId()).orElseThrow(); }

    private long logs(String acao) {
        return ctx.logs.ultimos(500).stream().filter(l -> l.acao().equals(acao)).count();
    }

    // ---------- geracao ----------
    @Test
    void geraDatasSemanais() {
        List<LocalDateTime> d = AgendaService.gerarDatas(PRIMEIRA, Frequencia.SEMANAL, 4);
        assertEquals(List.of(PRIMEIRA, PRIMEIRA.plusDays(7), PRIMEIRA.plusDays(14), PRIMEIRA.plusDays(21)), d);
    }

    @Test
    void geraDatasQuinzenais() {
        List<LocalDateTime> d = AgendaService.gerarDatas(PRIMEIRA, Frequencia.QUINZENAL, 3);
        assertEquals(List.of(PRIMEIRA, PRIMEIRA.plusDays(14), PRIMEIRA.plusDays(28)), d);
    }

    @Test
    void totalDeSessoesFicaEntre2e52() {
        assertEquals(2, AgendaService.gerarDatas(PRIMEIRA, Frequencia.SEMANAL, 2).size());
        assertEquals(52, AgendaService.gerarDatas(PRIMEIRA, Frequencia.SEMANAL, 52).size());
        assertThrows(RegraNegocioException.class, () -> AgendaService.gerarDatas(PRIMEIRA, Frequencia.SEMANAL, 1));
        assertThrows(RegraNegocioException.class, () -> AgendaService.gerarDatas(PRIMEIRA, Frequencia.SEMANAL, 53));
    }

    // ---------- previa ----------
    @Test
    void previaSemConflitoMarcaTudoOk() {
        List<ItemPrevia> p = ctx.agenda.previa(params(), AgendaService.gerarDatas(PRIMEIRA, Frequencia.SEMANAL, 5));
        assertEquals(5, p.size());
        assertTrue(p.stream().allMatch(ItemPrevia::ok));
        assertEquals(0, ctx.atendimentos.listarPorPaciente(joao.getId()).size());   // previa nao grava
    }

    @Test
    void previaDetectaConflitoDeHorario() {
        ctx.agenda.agendar(maria.getId(), ana.getId(), sessao.getId(), PRIMEIRA.plusDays(14),
                Modalidade.PRESENCIAL, false);
        List<ItemPrevia> p = ctx.agenda.previa(params(), AgendaService.gerarDatas(PRIMEIRA, Frequencia.SEMANAL, 4));
        assertEquals(SituacaoPrevia.OK, p.get(0).situacao());
        assertEquals(SituacaoPrevia.OK, p.get(1).situacao());
        assertEquals(SituacaoPrevia.CONFLITO, p.get(2).situacao());
        assertEquals(SituacaoPrevia.OK, p.get(3).situacao());
        assertNotNull(p.get(2).motivo());
    }

    @Test
    void previaDetectaSobreposicaoDentroDaPropriaSerie() {
        List<ItemPrevia> p = ctx.agenda.previa(params(), List.of(PRIMEIRA, PRIMEIRA.plusMinutes(10)));
        assertEquals(SituacaoPrevia.OK, p.get(0).situacao());
        assertEquals(SituacaoPrevia.CONFLITO, p.get(1).situacao());
    }

    @Test
    void previaMarcaDataForaDoPrazo() {
        // quinzenal x 52 passa de 1 ano a frente
        List<ItemPrevia> p = ctx.agenda.previa(params(), AgendaService.gerarDatas(PRIMEIRA, Frequencia.QUINZENAL, 52));
        assertEquals(SituacaoPrevia.OK, p.get(0).situacao());
        assertEquals(SituacaoPrevia.INVALIDA, p.get(51).situacao());
    }

    // ---------- gravacao ----------
    @Test
    void agendarLoteNumeraSerieELogaUmaVez() {
        List<Atendimento> criados = serieSemanal(4);
        assertEquals(4, criados.size());
        Atendimento terceiro = recarregar(criados.get(2));
        assertEquals("sessão 3 de 4", terceiro.rotuloSerie());
        assertEquals(criados.get(0).getSerieId(), terceiro.getSerieId());
        assertEquals(PRIMEIRA.plusDays(14), terceiro.getInicio());
        assertEquals(20000, terceiro.getValorCentavos());

        assertEquals(1, logs("AGENDAMENTO_LOTE"));
        assertEquals(0, logs("AGENDAMENTO"));   // so o log do lote
        String detalhe = ctx.logs.ultimos(50).stream().filter(l -> l.acao().equals("AGENDAMENTO_LOTE"))
                .findFirst().orElseThrow().detalhe();
        assertTrue(detalhe.contains("serie_id: " + terceiro.getSerieId()), detalhe);
        assertTrue(detalhe.contains("quantidade: 4"), detalhe);
        assertFalse(detalhe.contains("Joao"), detalhe);   // log nao guarda nome de paciente
    }

    @Test
    void atendimentoAvulsoNaoTemSerie() {
        Atendimento a = ctx.agenda.agendar(joao.getId(), ana.getId(), sessao.getId(), PRIMEIRA,
                Modalidade.PRESENCIAL, false);
        assertFalse(recarregar(a).isDeSerie());
        assertEquals("", recarregar(a).rotuloSerie());
    }

    @Test
    void pularConflitosSalvaSoAsSessoesLivres() {
        ctx.agenda.agendar(maria.getId(), ana.getId(), sessao.getId(), PRIMEIRA.plusDays(7),
                Modalidade.PRESENCIAL, false);
        List<LocalDateTime> datas = AgendaService.gerarDatas(PRIMEIRA, Frequencia.SEMANAL, 4);
        List<LocalDateTime> livres = ctx.agenda.previa(params(), datas).stream()
                .filter(ItemPrevia::ok).map(ItemPrevia::inicio).toList();
        assertEquals(3, livres.size());
        List<Atendimento> criados = ctx.agenda.agendarLote(params(), livres);
        assertEquals("sessão 1 de 3", recarregar(criados.get(0)).rotuloSerie());
        assertEquals("sessão 3 de 3", recarregar(criados.get(2)).rotuloSerie());
        assertEquals(3, ctx.atendimentos.listarPorPaciente(joao.getId()).size());
    }

    @Test
    void ajusteManualResolveOConflito() {
        ctx.agenda.agendar(maria.getId(), ana.getId(), sessao.getId(), PRIMEIRA.plusDays(7),
                Modalidade.PRESENCIAL, false);
        List<LocalDateTime> datas = new java.util.ArrayList<>(AgendaService.gerarDatas(PRIMEIRA, Frequencia.SEMANAL, 3));
        datas.set(1, datas.get(1).plusHours(2));   // ajuste manual da sessao em conflito
        assertTrue(ctx.agenda.previa(params(), datas).stream().allMatch(ItemPrevia::ok));
        assertEquals(3, ctx.agenda.agendarLote(params(), datas).size());
    }

    @Test
    void rollbackSeUmaSessaoForaDoPrazo() {
        List<LocalDateTime> datas = List.of(PRIMEIRA, PRIMEIRA.plusDays(7), LocalDate.of(2030, 1, 1).atTime(8, 0));
        RegraNegocioException e = assertThrows(RegraNegocioException.class, () -> ctx.agenda.agendarLote(params(), datas));
        assertTrue(e.getMessage().contains("Nenhuma sessão foi salva"), e.getMessage());
        assertEquals(0, ctx.atendimentos.listarPorPaciente(joao.getId()).size());   // nem as 2 primeiras
        assertEquals(0, logs("AGENDAMENTO_LOTE"));
    }

    @Test
    void rollbackSeUmaSessaoConflitaNaGravacao() {
        ctx.agenda.agendar(maria.getId(), ana.getId(), sessao.getId(), PRIMEIRA.plusDays(14),
                Modalidade.PRESENCIAL, false);
        // sem passar pela previa: o service revalida e desfaz tudo
        RegraNegocioException e = assertThrows(RegraNegocioException.class, () ->
                ctx.agenda.agendarLote(params(), AgendaService.gerarDatas(PRIMEIRA, Frequencia.SEMANAL, 4)));
        assertTrue(e.getMessage().contains("03/11/2026 às 08:00"), e.getMessage());
        assertEquals(0, ctx.atendimentos.listarPorPaciente(joao.getId()).size());
        assertEquals(0, logs("AGENDAMENTO_LOTE"));
    }

    // ---------- cancelar / editar com escopo ----------
    @Test
    void cancelarSoEstaNaoMexeNasOutras() {
        List<Atendimento> s = serieSemanal(4);
        ctx.agenda.alterarStatus(s.get(1).getId(), StatusAtendimento.CANCELADO, EscopoSerie.SO_ESTA);
        assertEquals(StatusAtendimento.AGENDADO, recarregar(s.get(0)).getStatus());
        assertEquals(StatusAtendimento.CANCELADO, recarregar(s.get(1)).getStatus());
        assertEquals(StatusAtendimento.AGENDADO, recarregar(s.get(2)).getStatus());
        assertEquals("sessão 2 de 4", recarregar(s.get(1)).rotuloSerie());   // cancelada continua na contagem
    }

    @Test
    void cancelarEstaEAsProximasRespeitaRealizadasEAnteriores() {
        List<Atendimento> s = serieSemanal(6);
        ctx.agenda.alterarStatus(s.get(0).getId(), StatusAtendimento.REALIZADO);      // anterior, ja feita
        ctx.agenda.alterarStatus(s.get(3).getId(), StatusAtendimento.CONFIRMADO);     // confirmada entra
        ctx.agenda.alterarStatus(s.get(4).getId(), StatusAtendimento.REALIZADO);      // futura mas realizada: fica
        ctx.agenda.alterarStatus(s.get(5).getId(), StatusAtendimento.CANCELADO);      // ja cancelada: fica

        assertEquals(2, ctx.agenda.contarProximosDaSerie(s.get(1).getId()));   // sessoes 3 e 4 (agendada e confirmada)
        ctx.agenda.alterarStatus(s.get(1).getId(), StatusAtendimento.CANCELADO, EscopoSerie.ESTA_E_PROXIMAS);

        assertEquals(StatusAtendimento.REALIZADO, recarregar(s.get(0)).getStatus());   // anterior intacta
        assertEquals(StatusAtendimento.CANCELADO, recarregar(s.get(1)).getStatus());
        assertEquals(StatusAtendimento.CANCELADO, recarregar(s.get(2)).getStatus());
        assertEquals(StatusAtendimento.CANCELADO, recarregar(s.get(3)).getStatus());
        assertEquals(StatusAtendimento.REALIZADO, recarregar(s.get(4)).getStatus());   // realizada preservada
        assertEquals(StatusAtendimento.CANCELADO, recarregar(s.get(5)).getStatus());
    }

    @Test
    void editarEstaEAsProximasDesloca() {
        List<Atendimento> s = serieSemanal(4);
        Atendimento segundo = recarregar(s.get(1));
        ctx.agenda.editar(segundo.getId(), null, sessao.getId(), segundo.getInicio().plusHours(1), 50,
                Modalidade.PRESENCIAL, null, EscopoSerie.ESTA_E_PROXIMAS);
        assertEquals(PRIMEIRA, recarregar(s.get(0)).getInicio());                      // anterior intacta
        assertEquals(PRIMEIRA.plusDays(7).plusHours(1), recarregar(s.get(1)).getInicio());
        assertEquals(PRIMEIRA.plusDays(14).plusHours(1), recarregar(s.get(2)).getInicio());
        assertEquals(PRIMEIRA.plusDays(21).plusHours(1), recarregar(s.get(3)).getInicio());
    }

    @Test
    void editarSoEstaMudaUmaSessao() {
        List<Atendimento> s = serieSemanal(3);
        Atendimento segundo = recarregar(s.get(1));
        ctx.agenda.editar(segundo.getId(), null, sessao.getId(), segundo.getInicio().plusHours(1), 50,
                Modalidade.PRESENCIAL, null, EscopoSerie.SO_ESTA);
        assertEquals(PRIMEIRA.plusDays(7).plusHours(1), recarregar(s.get(1)).getInicio());
        assertEquals(PRIMEIRA.plusDays(14), recarregar(s.get(2)).getInicio());
    }

    @Test
    void editarEstaEAsProximasComConflitoDesfazTudoEMostraDataEHora() {
        List<Atendimento> s = serieSemanal(4);
        // Maria ocupa o novo horario da 3a sessao (03/11 as 09:00)
        ctx.agenda.agendar(maria.getId(), ana.getId(), sessao.getId(), PRIMEIRA.plusDays(14).plusHours(1),
                Modalidade.PRESENCIAL, false);
        Atendimento segundo = recarregar(s.get(1));
        RegraNegocioException e = assertThrows(RegraNegocioException.class, () ->
                ctx.agenda.editar(segundo.getId(), null, sessao.getId(), segundo.getInicio().plusHours(1), 50,
                        Modalidade.PRESENCIAL, null, EscopoSerie.ESTA_E_PROXIMAS));
        assertTrue(e.getMessage().contains("03/11/2026 às 09:00"), e.getMessage());
        assertTrue(e.getMessage().contains("Nenhuma sessão da série foi alterada"), e.getMessage());
        for (int i = 0; i < s.size(); i++)   // tudo desfeito
            assertEquals(PRIMEIRA.plusDays(7L * i), recarregar(s.get(i)).getInicio(), "sessão " + (i + 1));
    }

    // ---------- troca de paciente ----------
    @Test
    void sessaoDeSerieNaoTrocaDePaciente() {
        List<Atendimento> s = serieSemanal(3);
        Atendimento a = s.get(0);
        assertEquals("Faz parte de uma série.", ctx.agenda.motivoBloqueioTrocaPaciente(a.getId()).orElseThrow());
        RegraNegocioException e = assertThrows(RegraNegocioException.class, () ->
                ctx.agenda.editar(a.getId(), maria.getId(), sessao.getId(), a.getInicio(), 50,
                        Modalidade.PRESENCIAL, null));
        assertTrue(e.getMessage().contains("Faz parte de uma série"), e.getMessage());
        assertEquals(joao.getId(), recarregar(a).getPacienteId());
    }

    // ---------- migracao ----------
    @Test
    void migracaoV12AcrescentaColunasEmBancoAntigo() throws SQLException {
        Atendimento avulso = ctx.agenda.agendar(joao.getId(), ana.getId(), sessao.getId(), PRIMEIRA,
                Modalidade.PRESENCIAL, false);
        ctx.close();
        try (Connection con = DriverManager.getConnection(url()); Statement st = con.createStatement()) {
            st.execute("DROP INDEX IF EXISTS idx_atendimento_serie");
            st.execute("ALTER TABLE atendimento DROP COLUMN serie_id");
            st.execute("ALTER TABLE atendimento DROP COLUMN numero_na_serie");
            st.execute("PRAGMA user_version = 11");
        }
        Path copia = tmp.resolve("teste.db.pre-v" + Database.VERSAO_ESQUEMA);
        assertTrue(Database.VERSAO_ESQUEMA >= 12);   // a v12 e a que este teste exercita; o nome da copia acompanha a atual
        assertFalse(Files.exists(copia));

        ctx = new Contexto(url());
        ZoneId zona = ZoneId.systemDefault();
        ctx.setRelogio(Clock.fixed(LocalDate.of(2026, 10, 12).atStartOfDay(zona).toInstant(), zona));
        ctx.autenticacao.entrar("ana", "senhaForte12".toCharArray());

        assertTrue(Files.exists(copia));
        assertEquals(11, userVersion(copia));                                  // estado de antes da migracao
        assertEquals(Database.VERSAO_ESQUEMA, userVersion(tmp.resolve("teste.db")));   // sobe direto para a atual
        Atendimento depois = ctx.atendimentos.buscar(avulso.getId()).orElseThrow();   // dado preservado
        assertEquals(PRIMEIRA, depois.getInicio());
        assertNull(depois.getSerieId());
        assertNull(depois.getNumeroNaSerie());
        // e a funcao nova funciona no banco migrado
        assertEquals(3, ctx.agenda.agendarLote(params(),
                AgendaService.gerarDatas(PRIMEIRA.plusDays(1), Frequencia.SEMANAL, 3)).size());
    }

    private int userVersion(Path banco) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + banco);
             Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }
}
