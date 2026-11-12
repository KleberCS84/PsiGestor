package br.com.kleber.psigestor.service;

import br.com.kleber.psigestor.model.*;
import br.com.kleber.psigestor.util.RegraNegocioException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Trocar o paciente de um agendamento, excluir paciente sem historico e arquivar/reativar.
 * "Hoje" fixo em 12/10/2026 (00:00): TERCA_14H (13/10) e futuro; ONTEM_10H (11/10) e passado.
 */
class TrocaPacienteExclusaoTest {

    private static final LocalDateTime TERCA_14H = LocalDateTime.of(2026, 10, 13, 14, 0);
    private static final LocalDateTime ONTEM_10H = LocalDateTime.of(2026, 10, 11, 10, 0);

    @TempDir Path tmp;
    Contexto ctx;
    Usuario ana;
    Servico sessao;
    Paciente joao;
    Paciente maria;

    @BeforeEach
    void preparar() {
        ctx = new Contexto("jdbc:sqlite:" + tmp.resolve("teste.db"));
        ZoneId zona = ZoneId.systemDefault();
        ctx.setRelogio(Clock.fixed(LocalDate.of(2026, 10, 12).atStartOfDay(zona).toInstant(), zona));
        ana = ctx.autenticacao.cadastrar("Ana Souza", "ana", "senhaForte12".toCharArray(),
                Papel.PSICOLOGO, "09/12345", 30);
        ctx.autenticacao.entrar("ana", "senhaForte12".toCharArray());
        sessao = ctx.servicos.salvar(new Servico("Psicoterapia individual", null, 50, 20000));
        joao = ctx.pacienteService.salvar(new Paciente("Joao Lima", LocalDate.of(1990, 5, 20), "62999990000"));
        maria = ctx.pacienteService.salvar(new Paciente("Maria Dias", null, null));
    }

    @AfterEach
    void fechar() { ctx.close(); }

    // ---------- helpers ----------
    private Atendimento agendar(Paciente p, LocalDateTime inicio) {
        return ctx.agenda.agendar(p.getId(), ana.getId(), sessao.getId(), inicio, Modalidade.PRESENCIAL, false);
    }

    private Atendimento trocar(Atendimento a, Paciente novo) {
        return ctx.agenda.editar(a.getId(), novo.getId(), a.getServicoId(), a.getInicio(), 50,
                Modalidade.PRESENCIAL, null);
    }

    private int pacienteDoAtendimento(Atendimento a) {
        return ctx.atendimentos.buscar(a.getId()).orElseThrow().getPacienteId();
    }

    private List<LogAcesso> logs(String acao) {
        return ctx.logs.ultimos(200).stream().filter(l -> l.acao().equals(acao)).toList();
    }

    private int contar(String sql) throws SQLException {
        try (Statement st = ctx.getConexaoParaTeste().createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private boolean existePaciente(int id) {
        return ctx.pacientes.buscar(id).isPresent();
    }

    // ---------- 1) trocar o paciente do agendamento ----------

    @Test
    void trocaDePacientePermitidaGravaLogSoComIds() {
        Atendimento a = agendar(joao, TERCA_14H);
        assertTrue(ctx.agenda.motivoBloqueioTrocaPaciente(a.getId()).isEmpty());

        Atendimento editado = trocar(a, maria);

        assertEquals(maria.getId(), editado.getPacienteId());
        assertEquals(maria.getId(), pacienteDoAtendimento(a));
        assertEquals("Maria Dias", ctx.atendimentos.buscar(a.getId()).orElseThrow().getPacienteNome());
        List<LogAcesso> log = logs("EDICAO_ATENDIMENTO_PACIENTE");
        assertEquals(1, log.size());
        assertEquals("atendimento", log.get(0).entidade());
        assertEquals(a.getId(), log.get(0).entidadeId());
        assertEquals("paciente_id: " + joao.getId() + " -> " + maria.getId(), log.get(0).detalhe());
        // sem nomes em nenhum log gravado por esta edicao
        for (LogAcesso l : ctx.logs.ultimos(50)) {
            String d = String.valueOf(l.detalhe());
            assertFalse(d.contains("Joao") || d.contains("Maria"), d);
        }
    }

    @Test
    void confirmadoTambemPodeTrocar() {
        Atendimento a = agendar(joao, TERCA_14H);
        ctx.agenda.alterarStatus(a.getId(), StatusAtendimento.CONFIRMADO);
        trocar(a, maria);
        assertEquals(maria.getId(), pacienteDoAtendimento(a));
    }

    @Test
    void editarMantendoOMesmoPacienteNaoGravaLogDeTroca() {
        Atendimento a = agendar(joao, TERCA_14H);
        ctx.agenda.editar(a.getId(), joao.getId(), a.getServicoId(), TERCA_14H.plusHours(1), 50,
                Modalidade.PRESENCIAL, null);
        ctx.agenda.editar(a.getId(), a.getServicoId(), TERCA_14H.plusHours(2), 50, Modalidade.PRESENCIAL, null);
        assertTrue(logs("EDICAO_ATENDIMENTO_PACIENTE").isEmpty());
        assertEquals(joao.getId(), pacienteDoAtendimento(a));
    }

    @Test
    void trocaVerificaConflitoDeHorarioDoNovoPaciente() {
        Atendimento a = agendar(joao, TERCA_14H);
        // Maria ja tem sessao com OUTRA psicologa no mesmo horario
        Usuario carla = ctx.autenticacao.cadastrar("Carla Melo", "carla", "senhaCarla12".toCharArray(),
                Papel.PSICOLOGO, "09/22222", 30);
        ctx.agenda.agendar(maria.getId(), carla.getId(), sessao.getId(), TERCA_14H.plusMinutes(20),
                Modalidade.PRESENCIAL, false);

        RegraNegocioException e = assertThrows(RegraNegocioException.class, () -> trocar(a, maria));

        assertTrue(e.getMessage().contains("Maria Dias"), e.getMessage());
        assertEquals(joao.getId(), pacienteDoAtendimento(a));
        assertTrue(logs("EDICAO_ATENDIMENTO_PACIENTE").isEmpty());
    }

    @Test
    void trocaExigeConsentimentoOnlineDoNovoPaciente() {
        Atendimento a = ctx.agenda.agendar(joao.getId(), ana.getId(), sessao.getId(), TERCA_14H, null,
                Modalidade.PRESENCIAL, false, null);
        assertThrows(RegraNegocioException.class, () -> ctx.agenda.editar(a.getId(), maria.getId(),
                a.getServicoId(), TERCA_14H, 50, Modalidade.ONLINE, null));
        assertEquals(joao.getId(), pacienteDoAtendimento(a));
    }

    @Test
    void trocaBloqueadaPeloStatus() {
        for (StatusAtendimento s : List.of(StatusAtendimento.REALIZADO, StatusAtendimento.FALTOU,
                StatusAtendimento.CANCELADO)) {
            Atendimento a = agendar(joao, TERCA_14H.plusDays(s.ordinal()));
            ctx.agenda.alterarStatus(a.getId(), s);
            String motivo = ctx.agenda.motivoBloqueioTrocaPaciente(a.getId()).orElseThrow();
            assertTrue(motivo.contains("agendados ou confirmados"), motivo);
            assertThrows(RegraNegocioException.class, () -> trocar(a, maria));
            assertEquals(joao.getId(), pacienteDoAtendimento(a));
        }
        assertTrue(logs("EDICAO_ATENDIMENTO_PACIENTE").isEmpty());
    }

    @Test
    void trocaBloqueadaPorProntuarioLigadoAoAtendimento() {
        Atendimento a = agendar(joao, TERCA_14H);
        ctx.prontuario.registrar(joao.getId(), a.getId(), "Registro da sessao");

        String motivo = ctx.agenda.motivoBloqueioTrocaPaciente(a.getId()).orElseThrow();
        assertTrue(motivo.contains("prontuário"), motivo);
        RegraNegocioException e = assertThrows(RegraNegocioException.class, () -> trocar(a, maria));
        assertTrue(e.getMessage().contains("prontuário"), e.getMessage());
        assertEquals(joao.getId(), pacienteDoAtendimento(a));
        assertTrue(logs("EDICAO_ATENDIMENTO_PACIENTE").isEmpty());

        // prontuario de OUTRO atendimento do mesmo paciente nao bloqueia este
        Atendimento outro = agendar(joao, TERCA_14H.plusDays(1));
        assertTrue(ctx.agenda.motivoBloqueioTrocaPaciente(outro.getId()).isEmpty());
    }

    @Test
    void trocaBloqueadaPorLancamentoFinanceiro() {
        Atendimento pago = agendar(joao, TERCA_14H);
        ctx.agenda.registrarPagamento(pago.getId(), "PIX");
        String motivo = ctx.agenda.motivoBloqueioTrocaPaciente(pago.getId()).orElseThrow();
        assertTrue(motivo.contains("financeiro"), motivo);
        RegraNegocioException e = assertThrows(RegraNegocioException.class, () -> trocar(pago, maria));
        assertTrue(e.getMessage().contains("financeiro"), e.getMessage());
        assertEquals(joao.getId(), pacienteDoAtendimento(pago));
        // editar outros campos mantendo o paciente continua permitido
        assertDoesNotThrow(() -> ctx.agenda.editar(pago.getId(), joao.getId(), pago.getServicoId(),
                TERCA_14H.plusHours(1), 50, Modalidade.PRESENCIAL, null));

        Atendimento recibo = agendar(joao, TERCA_14H.plusDays(1));
        ctx.agenda.marcarReciboEmitido(recibo.getId());
        assertTrue(ctx.agenda.motivoBloqueioTrocaPaciente(recibo.getId()).orElseThrow().contains("financeiro"));
        assertThrows(RegraNegocioException.class, () -> trocar(recibo, maria));
        assertTrue(logs("EDICAO_ATENDIMENTO_PACIENTE").isEmpty());
    }

    @Test
    void trocaParaPacienteArquivadoOuInexistenteERecusada() {
        Atendimento a = agendar(joao, TERCA_14H);
        ctx.pacienteService.arquivar(maria.getId(), true);
        assertThrows(RegraNegocioException.class, () -> trocar(a, maria));
        assertThrows(RegraNegocioException.class, () -> ctx.agenda.editar(a.getId(), 9999, a.getServicoId(),
                TERCA_14H, 50, Modalidade.PRESENCIAL, null));
        assertEquals(joao.getId(), pacienteDoAtendimento(a));
    }

    // ---------- 2) excluir paciente ----------

    @Test
    void excluirSemHistoricoApagaPacienteAgendamentosEConsentimentos() throws SQLException {
        Paciente carlos = ctx.pacienteService.salvar(new Paciente("Carlos Neto", null, null));
        ctx.pacienteService.registrarConsentimento(carlos.getId(), TipoConsentimento.CONTRATO_TERAPEUTICO, true, null);
        ctx.pacienteService.registrarConsentimento(carlos.getId(), TipoConsentimento.ATENDIMENTO_ONLINE, true, null);
        agendar(carlos, TERCA_14H);
        Atendimento cancelado = agendar(carlos, TERCA_14H.plusDays(1));
        ctx.agenda.alterarStatus(cancelado.getId(), StatusAtendimento.CANCELADO);
        Atendimento deJoao = agendar(joao, TERCA_14H.plusDays(3));   // outro paciente: nao pode ser tocado
        ctx.pacienteService.registrarConsentimento(joao.getId(), TipoConsentimento.CONTRATO_TERAPEUTICO, true, null);

        var h = ctx.pacienteService.historico(carlos.getId());
        assertFalse(h.temHistorico());
        assertEquals(1, h.agendamentosFuturos());

        ctx.pacienteService.excluir(carlos.getId());

        assertFalse(existePaciente(carlos.getId()));
        assertEquals(0, contar("SELECT COUNT(*) FROM atendimento WHERE paciente_id = " + carlos.getId()));
        assertEquals(0, contar("SELECT COUNT(*) FROM consentimento WHERE paciente_id = " + carlos.getId()));
        assertTrue(existePaciente(joao.getId()));
        assertEquals(1, contar("SELECT COUNT(*) FROM atendimento"));
        assertEquals(joao.getId(), pacienteDoAtendimento(deJoao));
        assertEquals(1, contar("SELECT COUNT(*) FROM consentimento"));
        List<LogAcesso> log = logs("EXCLUSAO_PACIENTE");
        assertEquals(1, log.size());
        assertEquals("paciente", log.get(0).entidade());
        assertEquals(carlos.getId(), log.get(0).entidadeId());
        assertNull(log.get(0).detalhe());   // so o id: nem nome, nem nada do cadastro
    }

    @Test
    void excluirPacienteSemNadaTambemFunciona() {
        Paciente novo = ctx.pacienteService.salvar(new Paciente("Sem Nada", null, null));
        ctx.pacienteService.excluir(novo.getId());
        assertFalse(existePaciente(novo.getId()));
    }

    @Test
    void excluirComHistoricoERecusadoPorCadaMotivo() throws SQLException {
        // 1) sessao realizada
        Paciente realizado = ctx.pacienteService.salvar(new Paciente("Com Realizado", null, null));
        ctx.agenda.alterarStatus(agendar(realizado, TERCA_14H).getId(), StatusAtendimento.REALIZADO);
        // 1b) falta tambem e historico
        Paciente faltou = ctx.pacienteService.salvar(new Paciente("Com Falta", null, null));
        ctx.agenda.alterarStatus(agendar(faltou, TERCA_14H.plusDays(1)).getId(), StatusAtendimento.FALTOU);
        // 2) prontuario sem atendimento
        Paciente prontuario = ctx.pacienteService.salvar(new Paciente("Com Prontuario", null, null));
        ctx.prontuario.registrar(prontuario.getId(), null, "Registro avulso");
        // 3) lancamento financeiro (pagamento antecipado de um agendamento futuro)
        Paciente financeiro = ctx.pacienteService.salvar(new Paciente("Com Financeiro", null, null));
        ctx.agenda.registrarPagamento(agendar(financeiro, TERCA_14H.plusDays(2)).getId(), "PIX");

        for (Paciente p : List.of(realizado, faltou, prontuario, financeiro)) {
            assertTrue(ctx.pacienteService.historico(p.getId()).temHistorico(), p.getNome());
            RegraNegocioException e = assertThrows(RegraNegocioException.class,
                    () -> ctx.pacienteService.excluir(p.getId()), p.getNome());
            assertTrue(e.getMessage().contains("5 anos"), e.getMessage());
            assertTrue(existePaciente(p.getId()), p.getNome());
        }
        assertEquals(3, contar("SELECT COUNT(*) FROM atendimento"));
        assertEquals(1, contar("SELECT COUNT(*) FROM evolucao"));
        assertTrue(logs("EXCLUSAO_PACIENTE").isEmpty());
        assertEquals(1, ctx.pacienteService.historico(realizado.getId()).atendimentosRealizados());
        assertEquals(1, ctx.pacienteService.historico(prontuario.getId()).registrosProntuario());
        assertEquals(1, ctx.pacienteService.historico(financeiro.getId()).lancamentosFinanceiros());
    }

    @Test
    void exclusaoQueFalhaDesfazTudo() throws SQLException {
        Paciente carlos = ctx.pacienteService.salvar(new Paciente("Carlos Neto", null, null));
        ctx.pacienteService.registrarConsentimento(carlos.getId(), TipoConsentimento.CONTRATO_TERAPEUTICO, true, null);
        agendar(carlos, TERCA_14H);
        try (Statement st = ctx.getConexaoParaTeste().createStatement()) {
            st.execute("CREATE TRIGGER teste_log_falha BEFORE INSERT ON log_acesso WHEN NEW.acao = 'EXCLUSAO_PACIENTE' "
                    + "BEGIN SELECT RAISE(ABORT, 'log indisponivel'); END");
        }
        assertThrows(RuntimeException.class, () -> ctx.pacienteService.excluir(carlos.getId()));
        // sem o log, nada foi apagado
        assertTrue(existePaciente(carlos.getId()));
        assertEquals(1, contar("SELECT COUNT(*) FROM atendimento WHERE paciente_id = " + carlos.getId()));
        assertEquals(1, contar("SELECT COUNT(*) FROM consentimento WHERE paciente_id = " + carlos.getId()));
    }

    // ---------- arquivar / reativar ----------

    @Test
    void arquivarTiraDasListasEReativarTraz() {
        assertTrue(ctx.pacienteService.pesquisar("", false).stream().anyMatch(p -> p.getId().equals(joao.getId())));

        ctx.pacienteService.arquivar(joao.getId(), true);

        assertTrue(ctx.pacienteService.pesquisar("", false).stream().noneMatch(p -> p.getId().equals(joao.getId())));
        assertTrue(ctx.pacienteService.pesquisar("Joao", false).isEmpty());
        assertTrue(ctx.pacienteService.pesquisar("", true).stream().anyMatch(p -> p.getId().equals(joao.getId())));
        assertTrue(ctx.pacienteService.aniversariantes(LocalDate.of(2026, 5, 20)).isEmpty());
        RegraNegocioException e = assertThrows(RegraNegocioException.class, () -> agendar(joao, TERCA_14H));
        assertTrue(e.getMessage().contains("arquivado"), e.getMessage());
        assertEquals(1, logs("ARQUIVAMENTO_PACIENTE").size());
        assertEquals(joao.getId(), logs("ARQUIVAMENTO_PACIENTE").get(0).entidadeId());
        assertNull(logs("ARQUIVAMENTO_PACIENTE").get(0).detalhe());

        ctx.pacienteService.arquivar(joao.getId(), false);

        assertTrue(ctx.pacienteService.pesquisar("", false).stream().anyMatch(p -> p.getId().equals(joao.getId())));
        assertDoesNotThrow(() -> agendar(joao, TERCA_14H));
        assertEquals(1, logs("REATIVACAO_PACIENTE").size());
        assertEquals(joao.getId(), logs("REATIVACAO_PACIENTE").get(0).entidadeId());
    }

    @Test
    void arquivarPodeCancelarSoOsAgendamentosFuturos() {
        Atendimento futuro = agendar(joao, TERCA_14H);
        Atendimento confirmado = agendar(joao, TERCA_14H.plusDays(1));
        ctx.agenda.alterarStatus(confirmado.getId(), StatusAtendimento.CONFIRMADO);
        Atendimento passado = agendar(joao, ONTEM_10H);   // ainda "agendado", mas ja passou
        assertEquals(2, ctx.pacienteService.historico(joao.getId()).agendamentosFuturos());

        ctx.pacienteService.arquivar(joao.getId(), true, true);

        assertEquals(StatusAtendimento.CANCELADO, ctx.atendimentos.buscar(futuro.getId()).orElseThrow().getStatus());
        assertEquals(StatusAtendimento.CANCELADO, ctx.atendimentos.buscar(confirmado.getId()).orElseThrow().getStatus());
        assertEquals(StatusAtendimento.AGENDADO, ctx.atendimentos.buscar(passado.getId()).orElseThrow().getStatus());
        assertTrue(ctx.pacienteService.pesquisar("Joao", false).isEmpty());
        assertFalse(logs("STATUS_AGENDADO->CANCELADO").isEmpty());
        assertEquals(1, logs("ARQUIVAMENTO_PACIENTE").size());
    }

    @Test
    void arquivarSemCancelarMantemOsAgendamentos() {
        Atendimento futuro = agendar(joao, TERCA_14H);
        ctx.pacienteService.arquivar(joao.getId(), true, false);
        assertEquals(StatusAtendimento.AGENDADO, ctx.atendimentos.buscar(futuro.getId()).orElseThrow().getStatus());
        // reativar nao mexe em agendamento, mesmo com o flag
        ctx.pacienteService.arquivar(joao.getId(), false, true);
        assertEquals(StatusAtendimento.AGENDADO, ctx.atendimentos.buscar(futuro.getId()).orElseThrow().getStatus());
    }

    @Test
    void arquivarNuncaApagaDados() throws SQLException {
        Paciente ana = ctx.pacienteService.salvar(new Paciente("Ana Fictícia", null, null));
        ctx.pacienteService.registrarConsentimento(ana.getId(), TipoConsentimento.CONTRATO_TERAPEUTICO, true, null);
        ctx.agenda.alterarStatus(agendar(ana, TERCA_14H).getId(), StatusAtendimento.REALIZADO);
        ctx.prontuario.registrar(ana.getId(), null, "Registro fictício");
        int atend = contar("SELECT COUNT(*) FROM atendimento WHERE paciente_id = " + ana.getId());
        int evol = contar("SELECT COUNT(*) FROM evolucao WHERE paciente_id = " + ana.getId());
        int cons = contar("SELECT COUNT(*) FROM consentimento WHERE paciente_id = " + ana.getId());

        ctx.pacienteService.arquivar(ana.getId(), true, true);

        assertEquals(atend, contar("SELECT COUNT(*) FROM atendimento WHERE paciente_id = " + ana.getId()));
        assertEquals(evol, contar("SELECT COUNT(*) FROM evolucao WHERE paciente_id = " + ana.getId()));
        assertEquals(cons, contar("SELECT COUNT(*) FROM consentimento WHERE paciente_id = " + ana.getId()));
        assertEquals("Ana Fictícia", ctx.pacienteService.buscar(ana.getId()).getNome());
        assertTrue(ctx.pacienteService.historico(ana.getId()).temHistorico());
        // arquivado com histórico continua sem poder ser excluído
        assertThrows(RegraNegocioException.class, () -> ctx.pacienteService.excluir(ana.getId()));
        assertTrue(existePaciente(ana.getId()));
    }

    @Test
    void arquivadoNaoApareceEmBuscaNemParaAgendar() {
        ctx.pacienteService.arquivar(maria.getId(), true);
        assertTrue(ctx.pacienteService.pesquisar("Maria", false).isEmpty());
        assertTrue(ctx.pacienteService.pesquisar("", false).stream().noneMatch(p -> p.getId().equals(maria.getId())));
        assertEquals(1, ctx.pacienteService.pesquisar("Maria", true).size());
        assertThrows(RegraNegocioException.class, () -> agendar(maria, TERCA_14H));
    }

    @Test
    void anonimizacaoContinuaIndependenteDaExclusao() {
        // a regra da guarda de 5 anos da anonimizacao nao mudou: paciente recente continua recusado
        RegraNegocioException e = assertThrows(RegraNegocioException.class,
                () -> ctx.pacienteService.anonimizarPaciente(joao.getId()));
        assertTrue(e.getMessage().contains("Anonimização"), e.getMessage());
    }
}
