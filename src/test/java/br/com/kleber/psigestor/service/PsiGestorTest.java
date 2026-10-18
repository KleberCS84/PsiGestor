package br.com.kleber.psigestor.service;

import br.com.kleber.psigestor.dao.DaoException;
import br.com.kleber.psigestor.model.*;
import br.com.kleber.psigestor.util.Criptografia;
import br.com.kleber.psigestor.util.RegraNegocioException;
import br.com.kleber.psigestor.util.Validadores;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PsiGestorTest {

    @TempDir Path tmp;
    Contexto ctx;
    Usuario ana;          // psicologa responsavel
    Servico sessao;
    Paciente joao;

    @BeforeEach
    void preparar() {
        ctx = new Contexto("jdbc:sqlite:" + tmp.resolve("teste.db"));
        // "hoje" fixo: as datas de 2026 usadas nos testes nunca saem da janela de agendamento
        ZoneId zona = ZoneId.systemDefault();
        ctx.setRelogio(Clock.fixed(LocalDate.of(2026, 10, 12).atStartOfDay(zona).toInstant(), zona));
        ana = ctx.autenticacao.cadastrar("Ana Souza", "ana", "senhaForte1".toCharArray(),
                Papel.PSICOLOGO, "09/12345", 30);
        ctx.autenticacao.entrar("ana", "senhaForte1".toCharArray());
        sessao = ctx.servicos.salvar(new Servico("Psicoterapia individual", null, 50, 200));
        joao = ctx.pacienteService.salvar(new Paciente("Joao Lima", LocalDate.of(1990, 5, 20), "62999990000"));
    }

    @AfterEach
    void fechar() { ctx.close(); }

    // ---------- autenticacao ----------
    @Test
    void loginComSenhaErradaFalhaEGravaLog() {
        ctx.sair();
        assertThrows(RegraNegocioException.class, () -> ctx.autenticacao.entrar("ana", "errada123".toCharArray()));
        assertTrue(ctx.logs.ultimos(5).stream().anyMatch(l -> l.acao().equals("LOGIN_FALHOU")));
    }

    @Test
    void senhaNaoEhGuardadaEmTextoPuro() {
        Usuario u = ctx.usuarios.buscarPorLogin("ana").orElseThrow();
        assertNotEquals("senhaForte1", u.getSenhaHash());
    }

    // ---------- troca de senha (criptografia de envelope) ----------
    @Test
    void trocaDeSenhaMantemProntuarioLegivel() {
        ctx.prontuario.registrar(joao.getId(), null, "Registro antes da troca");
        ctx.autenticacao.trocarSenha("senhaForte1".toCharArray(), "novaSenha22".toCharArray());
        ctx.sair();
        assertThrows(RegraNegocioException.class, () -> ctx.autenticacao.entrar("ana", "senhaForte1".toCharArray()));
        ctx.autenticacao.entrar("ana", "novaSenha22".toCharArray());
        assertEquals("Registro antes da troca", ctx.prontuario.listar(joao.getId()).get(0).texto());
        assertTrue(ctx.logs.ultimos(10).stream().anyMatch(l -> l.acao().equals("TROCA_SENHA")));
    }

    @Test
    void trocaDeSenhaExigeSenhaAtualCorreta() {
        assertThrows(RegraNegocioException.class, () ->
                ctx.autenticacao.trocarSenha("chuteErrado".toCharArray(), "novaSenha22".toCharArray()));
        assertThrows(RegraNegocioException.class, () ->        // nova senha curta demais
                ctx.autenticacao.trocarSenha("senhaForte1".toCharArray(), "curta".toCharArray()));
        ctx.sair();
        assertDoesNotThrow(() -> ctx.autenticacao.entrar("ana", "senhaForte1".toCharArray()));
    }

    /** Simula o banco real criado antes desta versao: sem as colunas novas e prontuario com a chave antiga. */
    @Test
    void bancoAntigoEMigradoSemPerderProntuario() throws SQLException {
        Usuario u = ctx.usuarios.buscarPorLogin("ana").orElseThrow();
        var chaveAntiga = Criptografia.derivarChave("senhaForte1".toCharArray(), u.getChaveSalt());
        byte[][] ivECifrado = Criptografia.cifrar("Registro do MVP", chaveAntiga);
        ctx.evolucoes.inserir(joao.getId(), null, ana.getId(), ivECifrado[1], ivECifrado[0], null);
        ctx.close();

        String url = "jdbc:sqlite:" + tmp.resolve("teste.db");
        try (Connection con = DriverManager.getConnection(url); Statement st = con.createStatement()) {
            st.execute("ALTER TABLE usuario DROP COLUMN chave_cifrada");
            st.execute("ALTER TABLE usuario DROP COLUMN chave_iv");
            st.execute("PRAGMA user_version = 0");
        }

        ctx = new Contexto(url);                                   // migracao roda aqui
        assertNull(ctx.usuarios.buscarPorLogin("ana").orElseThrow().getChaveCifrada());
        ctx.autenticacao.entrar("ana", "senhaForte1".toCharArray()); // e aqui a chave e embrulhada
        assertNotNull(ctx.usuarios.buscarPorLogin("ana").orElseThrow().getChaveCifrada());
        assertEquals("Registro do MVP", ctx.prontuario.listar(joao.getId()).get(0).texto());

        ctx.autenticacao.trocarSenha("senhaForte1".toCharArray(), "novaSenha22".toCharArray());
        ctx.sair();
        ctx.autenticacao.entrar("ana", "novaSenha22".toCharArray());
        assertEquals("Registro do MVP", ctx.prontuario.listar(joao.getId()).get(0).texto());
    }

    // ---------- prontuario ----------
    @Test
    void prontuarioFicaCifradoNoBanco() throws SQLException {
        ctx.prontuario.registrar(joao.getId(), null, "Paciente relata ansiedade no trabalho.");
        try (Statement st = ctx.getConexaoParaTeste().createStatement();
             ResultSet rs = st.executeQuery("SELECT conteudo_cifrado FROM evolucao")) {
            rs.next();
            String bruto = new String(rs.getBytes(1));
            assertFalse(bruto.contains("ansiedade"));
        }
        assertEquals("Paciente relata ansiedade no trabalho.", ctx.prontuario.listar(joao.getId()).get(0).texto());
    }

    @Test
    void prontuarioNaoPodeSerAlteradoNemApagado() {
        ctx.prontuario.registrar(joao.getId(), null, "Registro original");
        assertThrows(SQLException.class, () -> ctx.getConexaoParaTeste().createStatement()
                .executeUpdate("UPDATE evolucao SET profissional_id = 99"));
        assertThrows(SQLException.class, () -> ctx.getConexaoParaTeste().createStatement()
                .executeUpdate("DELETE FROM evolucao"));
    }

    @Test
    void retificacaoCriaNovoRegistroApontandoParaOriginal() {
        int original = ctx.prontuario.registrar(joao.getId(), null, "Texto com erro");
        ctx.prontuario.retificar(joao.getId(), original, "Texto corrigido");
        List<Evolucao> lista = ctx.prontuario.listar(joao.getId());
        assertEquals(2, lista.size());
        assertEquals(original, lista.get(0).retificaId());
    }

    @Test
    void secretariaNaoAcessaProntuario() {
        ctx.autenticacao.cadastrar("Bia Recepcao", "bia", "senhaBia123".toCharArray(), Papel.SECRETARIA, null, 0);
        ctx.autenticacao.entrar("bia", "senhaBia123".toCharArray());
        assertThrows(RegraNegocioException.class, () -> ctx.prontuario.listar(joao.getId()));
        assertThrows(RegraNegocioException.class, () -> ctx.prontuario.registrar(joao.getId(), null, "x"));
    }

    @Test
    void outroPsicologoNaoLeRegistroDeColega() {
        ctx.prontuario.registrar(joao.getId(), null, "Conteudo da Ana");
        ctx.autenticacao.cadastrar("Carlos", "carlos", "senhaCarlos1".toCharArray(), Papel.PSICOLOGO, "09/999", 30);
        ctx.autenticacao.entrar("carlos", "senhaCarlos1".toCharArray());
        assertEquals(ProntuarioService.SIGILOSO, ctx.prontuario.listar(joao.getId()).get(0).texto());
    }

    // ---------- agenda ----------
    @Test
    void naoPermiteDoisAtendimentosNoMesmoHorario() {
        LocalDateTime t = LocalDateTime.of(2026, 10, 13, 14, 0);
        ctx.agenda.agendar(joao.getId(), ana.getId(), sessao.getId(), t, Modalidade.PRESENCIAL, false);
        Paciente maria = ctx.pacienteService.salvar(new Paciente("Maria", LocalDate.of(1985, 1, 1), null));
        assertThrows(RegraNegocioException.class, () ->
                ctx.agenda.agendar(maria.getId(), ana.getId(), sessao.getId(), t.plusMinutes(30), Modalidade.PRESENCIAL, false));
        // encostado no fim (14:50) nao conflita
        assertDoesNotThrow(() ->
                ctx.agenda.agendar(maria.getId(), ana.getId(), sessao.getId(), t.plusMinutes(50), Modalidade.PRESENCIAL, false));
    }

    @Test
    void cancelamentoLiberaHorario() {
        LocalDateTime t = LocalDateTime.of(2026, 10, 13, 9, 0);
        Atendimento a = ctx.agenda.agendar(joao.getId(), ana.getId(), sessao.getId(), t, Modalidade.PRESENCIAL, false);
        ctx.agenda.alterarStatus(a.getId(), StatusAtendimento.CANCELADO);
        assertDoesNotThrow(() -> ctx.agenda.agendar(joao.getId(), ana.getId(), sessao.getId(), t, Modalidade.PRESENCIAL, false));
    }

    @Test
    void atendimentoOnlineExigeConsentimento() {
        LocalDateTime t = LocalDateTime.of(2026, 10, 14, 10, 0);
        assertThrows(RegraNegocioException.class, () ->
                ctx.agenda.agendar(joao.getId(), ana.getId(), sessao.getId(), t, Modalidade.ONLINE, false));
        ctx.pacienteService.registrarConsentimento(joao.getId(), TipoConsentimento.ATENDIMENTO_ONLINE, true, "assinado");
        assertDoesNotThrow(() -> ctx.agenda.agendar(joao.getId(), ana.getId(), sessao.getId(), t, Modalidade.ONLINE, false));
    }

    @Test
    void valorDoConvenioSubstituiValorParticular() {
        Convenio c = ctx.convenios.salvar(new Convenio("Unimed Teste", "123456", 90, 30));
        joao.setConvenioId(c.getId());
        ctx.pacienteService.salvar(joao);
        Atendimento a = ctx.agenda.agendar(joao.getId(), ana.getId(), sessao.getId(),
                LocalDateTime.of(2026, 10, 15, 8, 0), Modalidade.PRESENCIAL, true);
        assertEquals(90, a.getValor());
    }

    // ---------- A3: maquina de estados do status ----------
    /** Cenario da revisao: reativar um cancelado nao pode criar dois atendimentos no mesmo horario. */
    @Test
    void reativarCanceladoRevalidaConflito() {
        LocalDateTime t = LocalDateTime.of(2026, 10, 13, 14, 0);
        Atendimento a = ctx.agenda.agendar(joao.getId(), ana.getId(), sessao.getId(), t, Modalidade.PRESENCIAL, false);
        ctx.agenda.alterarStatus(a.getId(), StatusAtendimento.CANCELADO);
        Paciente maria = ctx.pacienteService.salvar(new Paciente("Maria", LocalDate.of(1985, 1, 1), null));
        ctx.agenda.agendar(maria.getId(), ana.getId(), sessao.getId(), t, Modalidade.PRESENCIAL, false);
        assertThrows(RegraNegocioException.class, () -> ctx.agenda.alterarStatus(a.getId(), StatusAtendimento.CONFIRMADO));
    }

    @Test
    void transicaoInvalidaDeStatusERecusada() {
        Atendimento a = ctx.agenda.agendar(joao.getId(), ana.getId(), sessao.getId(),
                LocalDateTime.of(2026, 10, 13, 8, 0), Modalidade.PRESENCIAL, false);
        ctx.agenda.alterarStatus(a.getId(), StatusAtendimento.REALIZADO);
        assertThrows(RegraNegocioException.class, () -> ctx.agenda.alterarStatus(a.getId(), StatusAtendimento.AGENDADO));
        assertThrows(RegraNegocioException.class, () -> ctx.agenda.alterarStatus(a.getId(), StatusAtendimento.REALIZADO));
        assertThrows(RegraNegocioException.class, () -> ctx.agenda.alterarStatus(a.getId(), null));
        assertDoesNotThrow(() -> ctx.agenda.alterarStatus(a.getId(), StatusAtendimento.FALTOU));   // correcao de clique
    }

    @Test
    void auditoriaRegistraStatusAnterior() {
        Atendimento a = ctx.agenda.agendar(joao.getId(), ana.getId(), sessao.getId(),
                LocalDateTime.of(2026, 10, 13, 8, 0), Modalidade.PRESENCIAL, false);
        ctx.agenda.alterarStatus(a.getId(), StatusAtendimento.REALIZADO);
        assertTrue(ctx.logs.ultimos(5).stream().anyMatch(l -> l.acao().equals("STATUS_AGENDADO->REALIZADO")));
    }

    // ---------- A4: update em id inexistente ----------
    @Test
    void operacaoEmIdInexistenteFalhaENaoAudita() {
        assertThrows(RegraNegocioException.class, () -> ctx.agenda.registrarPagamento(999, "PIX"));
        assertThrows(RegraNegocioException.class, () -> ctx.agenda.marcarReciboEmitido(999));
        assertTrue(ctx.logs.ultimos(10).stream().noneMatch(l -> l.acao().equals("PAGAMENTO")));
        Convenio fantasma = new Convenio("Fantasma", null, 90, 30);
        fantasma.setId(999);
        assertThrows(RegraNegocioException.class, () -> ctx.convenios.salvar(fantasma));
    }

    // ---------- M9: datas e conflito por paciente ----------
    @Test
    void agendamentoForaDaJanelaERecusado() {
        assertThrows(RegraNegocioException.class, () -> ctx.agenda.agendar(joao.getId(), ana.getId(), sessao.getId(),
                LocalDateTime.of(2062, 10, 13, 14, 0), Modalidade.PRESENCIAL, false));
        assertThrows(RegraNegocioException.class, () -> ctx.agenda.agendar(joao.getId(), ana.getId(), sessao.getId(),
                LocalDateTime.of(2026, 8, 1, 14, 0), Modalidade.PRESENCIAL, false));
    }

    @Test
    void mesmoPacienteNaoPodeTerHorariosSobrepostos() {
        Usuario carlos = ctx.autenticacao.cadastrar("Carlos", "carlos", "senhaCarlos1".toCharArray(), Papel.PSICOLOGO, "09/999", 30);
        LocalDateTime t = LocalDateTime.of(2026, 10, 13, 14, 0);
        ctx.agenda.agendar(joao.getId(), ana.getId(), sessao.getId(), t, Modalidade.PRESENCIAL, false);
        assertThrows(RegraNegocioException.class, () ->
                ctx.agenda.agendar(joao.getId(), carlos.getId(), sessao.getId(), t.plusMinutes(30), Modalidade.PRESENCIAL, false));
    }

    // ---------- convenio: CHECK em prazo_pagamento_dias ----------
    @Test
    void prazoDePagamentoNegativoERecusadoPeloBanco() {
        assertThrows(DaoException.class, () -> ctx.convenios.salvar(new Convenio("Negativo", null, 90, -1)));
    }

    // ---------- pacientes ----------
    @Test
    void menorDeIdadeExigeResponsavel() {
        Paciente crianca = new Paciente("Pedro", LocalDate.now().minusYears(10), null);
        assertThrows(RegraNegocioException.class, () -> ctx.pacienteService.salvar(crianca));
        crianca.setResponsavelLegal("Mae do Pedro");
        assertDoesNotThrow(() -> ctx.pacienteService.salvar(crianca));
    }

    @Test
    void cpfInvalidoERecusado() {
        assertTrue(Validadores.cpfValido("529.982.247-25"));
        assertFalse(Validadores.cpfValido("111.111.111-11"));
        joao.setCpf("123.456.789-00");
        assertThrows(RegraNegocioException.class, () -> ctx.pacienteService.salvar(joao));
    }

    @Test
    void exportacaoLgpdGeraArquivo() throws Exception {
        Path arq = ctx.pacienteService.exportarDadosDoTitular(joao.getId(), tmp.resolve("export"));
        assertTrue(Files.readString(arq).contains("Joao Lima"));
    }

    // ---------- financeiro ----------
    @Test
    void resumoERepasse() {
        LocalDate dia = LocalDate.of(2026, 10, 20);
        Atendimento a1 = ctx.agenda.agendar(joao.getId(), ana.getId(), sessao.getId(), dia.atTime(8, 0), Modalidade.PRESENCIAL, false);
        Atendimento a2 = ctx.agenda.agendar(joao.getId(), ana.getId(), sessao.getId(), dia.atTime(9, 0), Modalidade.PRESENCIAL, false);
        Atendimento a3 = ctx.agenda.agendar(joao.getId(), ana.getId(), sessao.getId(), dia.atTime(10, 0), Modalidade.PRESENCIAL, false);
        ctx.agenda.alterarStatus(a1.getId(), StatusAtendimento.REALIZADO);
        ctx.agenda.registrarPagamento(a1.getId(), "PIX");
        ctx.agenda.alterarStatus(a2.getId(), StatusAtendimento.REALIZADO);
        ctx.agenda.alterarStatus(a3.getId(), StatusAtendimento.FALTOU);

        FinanceiroService.Resumo r = ctx.financeiro.resumo(dia, dia);
        assertEquals(400, r.faturado());
        assertEquals(200, r.recebido());
        assertEquals(200, r.aReceber());
        assertEquals(1, r.faltas());
        assertEquals(1, r.recibosPendentes());

        FinanceiroService.LinhaRepasse l = ctx.financeiro.repasse(dia, dia).get(0);
        assertEquals(120, l.parteClinica());       // 30% de 400
        assertEquals(280, l.liquidoProfissional());
    }

    @Test
    void backupGeraArquivo() {
        Path b = ctx.backup.gerar(tmp.resolve("backups"));
        assertTrue(Files.exists(b));
    }

    // ---------- A1: servicos, convenios e repasse via service ----------
    @Test
    void secretariaNaoAlteraServicoConvenioNemRepasse() {
        ctx.autenticacao.cadastrar("Bia Recepcao", "bia", "senhaBia123".toCharArray(), Papel.SECRETARIA, null, 0);
        ctx.autenticacao.entrar("bia", "senhaBia123".toCharArray());
        assertThrows(RegraNegocioException.class, () ->
                ctx.cadastros.salvarServico(new Servico("Avaliacao", null, 50, 10)));
        assertThrows(RegraNegocioException.class, () ->
                ctx.cadastros.salvarConvenio(new Convenio("Barato", null, 1, 30)));
        assertThrows(RegraNegocioException.class, () -> ctx.cadastros.atualizarRepasse(ana.getId(), 0));
        assertFalse(ctx.cadastros.listarServicos().isEmpty());   // ver continua permitido
    }

    @Test
    void psicologoAlteraCadastrosComAuditoria() {
        ctx.cadastros.salvarServico(new Servico("Avaliacao", "50000470", 50, 250));
        Convenio c = ctx.cadastros.salvarConvenio(new Convenio("Unimed Teste", "123456", 90, 30));
        ctx.cadastros.salvarConvenio(c);
        ctx.cadastros.atualizarRepasse(ana.getId(), 40);
        var acoes = ctx.logs.ultimos(10).stream().map(l -> l.acao()).toList();
        assertTrue(acoes.containsAll(List.of("CADASTRO_SERVICO", "CADASTRO_CONVENIO", "ALTERACAO_CONVENIO",
                "ALTERACAO_REPASSE")));
        assertEquals(40, ctx.usuarios.buscarPorLogin("ana").orElseThrow().getPercentualRepasseClinica());
    }

    @Test
    void repasseInvalidoOuDeUsuarioInexistenteERecusadoSemAuditar() {
        assertThrows(RegraNegocioException.class, () -> ctx.cadastros.atualizarRepasse(ana.getId(), 150));
        assertThrows(RegraNegocioException.class, () -> ctx.cadastros.atualizarRepasse(999, 10));
        assertTrue(ctx.logs.ultimos(10).stream().noneMatch(l -> l.acao().equals("ALTERACAO_REPASSE")));
    }

    @Test
    void cadastrosExigemSessao() {
        ctx.sair();
        assertThrows(RegraNegocioException.class, () ->
                ctx.cadastros.salvarServico(new Servico("Avaliacao", null, 50, 10)));
        assertThrows(RegraNegocioException.class, () -> ctx.cadastros.listarConvenios());
    }

    // ---------- A2: desativar usuario ----------
    @Test
    void psicologoDesativaUsuarioSemApagar() {
        Usuario bia = ctx.autenticacao.cadastrar("Bia Recepcao", "bia", "senhaBia123".toCharArray(), Papel.SECRETARIA, null, 0);
        ctx.autenticacao.desativar(bia.getId());
        assertTrue(ctx.logs.ultimos(5).stream().anyMatch(l -> l.acao().equals("DESATIVACAO_USUARIO")));
        assertFalse(ctx.usuarios.buscar(bia.getId()).orElseThrow().isAtivo());   // registro continua no banco
        assertThrows(RegraNegocioException.class, () -> ctx.autenticacao.desativar(bia.getId()));   // ja inativo
        ctx.sair();
        assertThrows(RegraNegocioException.class, () -> ctx.autenticacao.entrar("bia", "senhaBia123".toCharArray()));
    }

    @Test
    void secretariaNaoDesativaNinguem() {
        ctx.autenticacao.cadastrar("Bia Recepcao", "bia", "senhaBia123".toCharArray(), Papel.SECRETARIA, null, 0);
        ctx.autenticacao.entrar("bia", "senhaBia123".toCharArray());
        assertThrows(RegraNegocioException.class, () -> ctx.autenticacao.desativar(ana.getId()));
        assertTrue(ctx.usuarios.buscar(ana.getId()).orElseThrow().isAtivo());
    }

    @Test
    void naoDesativaPropriaContaNemUltimoPsicologo() {
        assertThrows(RegraNegocioException.class, () -> ctx.autenticacao.desativar(ana.getId()));
        Usuario carlos = ctx.autenticacao.cadastrar("Carlos", "carlos", "senhaCarlos1".toCharArray(), Papel.PSICOLOGO, "09/999", 30);
        ctx.autenticacao.entrar("carlos", "senhaCarlos1".toCharArray());
        ctx.autenticacao.desativar(ana.getId());
        // sessao antiga da Ana (ex.: outra instancia aberta no mesmo arquivo) tenta tirar o ultimo psicologo
        ctx.setSessao(new br.com.kleber.psigestor.util.Sessao(ana, null));
        assertThrows(RegraNegocioException.class, () -> ctx.autenticacao.desativar(carlos.getId()));
        assertTrue(ctx.usuarios.buscar(carlos.getId()).orElseThrow().isAtivo());
    }

    // ---------- M1: tentativas de login e inatividade ----------
    @Test
    void cincoFalhasBloqueiamAteASenhaCerta() {
        RelogioAjustavel r = relogioAjustavel();
        ctx.sair();
        for (int i = 0; i < 5; i++)
            assertThrows(RegraNegocioException.class, () -> ctx.autenticacao.entrar("ana", "errada123".toCharArray()));
        var e = assertThrows(RegraNegocioException.class, () -> ctx.autenticacao.entrar("ana", "senhaForte1".toCharArray()));
        assertTrue(e.getMessage().contains("Muitas tentativas"));
        assertTrue(ctx.logs.ultimos(3).stream().anyMatch(l -> l.acao().equals("LOGIN_BLOQUEADO")));
        r.avancar(Duration.ofSeconds(30));
        assertDoesNotThrow(() -> ctx.autenticacao.entrar("ana", "senhaForte1".toCharArray()));
    }

    @Test
    void bloqueioDobraACadaNovaFalha() {
        RelogioAjustavel r = relogioAjustavel();
        ctx.sair();
        for (int i = 0; i < 5; i++)
            assertThrows(RegraNegocioException.class, () -> ctx.autenticacao.entrar("ana", "errada123".toCharArray()));
        r.avancar(Duration.ofSeconds(30));
        assertThrows(RegraNegocioException.class, () -> ctx.autenticacao.entrar("ana", "errada123".toCharArray())); // 6a: 60 s
        r.avancar(Duration.ofSeconds(30));
        assertThrows(RegraNegocioException.class, () -> ctx.autenticacao.entrar("ana", "senhaForte1".toCharArray()));
        r.avancar(Duration.ofSeconds(30));
        assertDoesNotThrow(() -> ctx.autenticacao.entrar("ana", "senhaForte1".toCharArray()));
    }

    @Test
    void loginCertoZeraContadorDeFalhas() {
        ctx.sair();
        for (int i = 0; i < 4; i++)
            assertThrows(RegraNegocioException.class, () -> ctx.autenticacao.entrar("ana", "errada123".toCharArray()));
        ctx.autenticacao.entrar("ana", "senhaForte1".toCharArray());
        ctx.sair();
        for (int i = 0; i < 4; i++)
            assertThrows(RegraNegocioException.class, () -> ctx.autenticacao.entrar("ana", "errada123".toCharArray()));
        assertDoesNotThrow(() -> ctx.autenticacao.entrar("ana", "senhaForte1".toCharArray()));
    }

    @Test
    void falhaDeLoginRegistraLoginTentado() {
        ctx.sair();
        assertThrows(RegraNegocioException.class, () -> ctx.autenticacao.entrar("ana", "errada123".toCharArray()));
        assertThrows(RegraNegocioException.class, () -> ctx.autenticacao.entrar(" fantasma ", "x".toCharArray()));
        var log = ctx.logs.ultimos(2);
        assertEquals("LOGIN_FALHOU", log.get(0).acao());
        assertEquals("login inexistente", log.get(0).detalhe());
        assertEquals("-", log.get(0).usuario());
        assertEquals("login=ana", log.get(1).detalhe());
        assertEquals("Ana Souza", log.get(1).usuario());
    }

    @Test
    void senhaDigitadaNoCampoLoginNaoVaiParaOLog() {
        ctx.sair();
        assertThrows(RegraNegocioException.class, () -> ctx.autenticacao.entrar("senhaForte1", "x".toCharArray()));
        var l = ctx.logs.ultimos(1).get(0);
        assertEquals("login inexistente", l.detalhe());
        assertFalse(l.detalhe().contains("senhaForte1"));
    }

    @Test
    void dezMinutosSemAtividadeEncerramASessao() {
        RelogioAjustavel r = relogioAjustavel();
        r.avancar(Contexto.TEMPO_INATIVIDADE);
        assertThrows(RegraNegocioException.class, () -> ctx.pacienteService.pesquisar("", false));
        assertNull(ctx.getSessao());
        assertTrue(ctx.logs.ultimos(3).stream().anyMatch(l -> l.acao().equals("LOGOUT_INATIVIDADE")));
    }

    @Test
    void atividadeAdiaOLogoutAutomatico() {
        RelogioAjustavel r = relogioAjustavel();
        r.avancar(Duration.ofMinutes(9));
        ctx.registrarAtividade();
        r.avancar(Duration.ofMinutes(9));
        assertFalse(ctx.sairSeInativo());
        assertDoesNotThrow(() -> ctx.pacienteService.pesquisar("", false));
        r.avancar(Duration.ofMinutes(10));
        ctx.registrarAtividade();                 // clique depois do limite nao revive a sessao
        assertNull(ctx.getSessao());
    }

    @Test
    void bancoV2GanhaColunaDetalheNoLog() throws SQLException {
        ctx.close();
        String url = "jdbc:sqlite:" + tmp.resolve("teste.db");
        try (Connection con = DriverManager.getConnection(url); Statement st = con.createStatement()) {
            st.execute("ALTER TABLE log_acesso DROP COLUMN detalhe");
            st.execute("PRAGMA user_version = 2");
        }
        ctx = new Contexto(url);
        assertThrows(RegraNegocioException.class, () -> ctx.autenticacao.entrar("ana", "errada123".toCharArray()));
        assertEquals("login=ana", ctx.logs.ultimos(1).get(0).detalhe());
    }

    // ---------- B8: validacoes de consentimento/pagamento e leituras com sessao ----------
    @Test
    void consentimentoExigeTipoEPacienteExistente() {
        assertThrows(RegraNegocioException.class, () ->
                ctx.pacienteService.registrarConsentimento(joao.getId(), null, true, "x"));
        assertThrows(RegraNegocioException.class, () ->
                ctx.pacienteService.registrarConsentimento(999, TipoConsentimento.ATENDIMENTO_ONLINE, true, "x"));
    }

    @Test
    void formaDePagamentoInvalidaERecusada() {
        Atendimento a = ctx.agenda.agendar(joao.getId(), ana.getId(), sessao.getId(),
                LocalDateTime.of(2026, 10, 13, 8, 0), Modalidade.PRESENCIAL, false);
        assertThrows(RegraNegocioException.class, () -> ctx.agenda.registrarPagamento(a.getId(), "Bitcoin"));
        assertThrows(RegraNegocioException.class, () -> ctx.agenda.registrarPagamento(a.getId(), null));
        assertFalse(ctx.atendimentos.buscar(a.getId()).orElseThrow().isPago());
    }

    @Test
    void naoPagaCanceladoNemPagaDuasVezes() {
        Atendimento cancelado = ctx.agenda.agendar(joao.getId(), ana.getId(), sessao.getId(),
                LocalDateTime.of(2026, 10, 13, 8, 0), Modalidade.PRESENCIAL, false);
        ctx.agenda.alterarStatus(cancelado.getId(), StatusAtendimento.CANCELADO);
        assertThrows(RegraNegocioException.class, () -> ctx.agenda.registrarPagamento(cancelado.getId(), "PIX"));

        Atendimento a = ctx.agenda.agendar(joao.getId(), ana.getId(), sessao.getId(),
                LocalDateTime.of(2026, 10, 13, 9, 0), Modalidade.PRESENCIAL, false);
        ctx.agenda.registrarPagamento(a.getId(), "PIX");
        assertThrows(RegraNegocioException.class, () -> ctx.agenda.registrarPagamento(a.getId(), "Dinheiro"));
        assertEquals("PIX", ctx.atendimentos.buscar(a.getId()).orElseThrow().getFormaPagamento());
    }

    @Test
    void leiturasDeAgendaEAniversariantesExigemSessao() {
        LocalDate dia = LocalDate.of(2026, 10, 13);
        ctx.sair();
        assertThrows(RegraNegocioException.class, () -> ctx.agenda.doDia(dia, null));
        assertThrows(RegraNegocioException.class, () -> ctx.agenda.periodo(dia, dia, null));
        assertThrows(RegraNegocioException.class, () -> ctx.pacienteService.aniversariantes(dia));
    }

    // ---------- M2: operacao + auditoria atomicas ----------
    /** Simula disco cheio/lock so na trilha de auditoria. */
    private void logFalhando(boolean falhar) throws SQLException {
        try (Statement st = ctx.getConexaoParaTeste().createStatement()) {
            if (falhar) st.execute("CREATE TRIGGER teste_log_falha BEFORE INSERT ON log_acesso "
                    + "BEGIN SELECT RAISE(ABORT, 'log indisponivel'); END");
            else st.execute("DROP TRIGGER teste_log_falha");
        }
    }

    private int contar(String tabela) throws SQLException {
        try (Statement st = ctx.getConexaoParaTeste().createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + tabela)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void prontuarioSemLogNaoEGravadoERepetirNaoDuplica() throws SQLException {
        logFalhando(true);
        assertThrows(DaoException.class, () -> ctx.prontuario.registrar(joao.getId(), null, "Sessao 1"));
        assertEquals(0, contar("evolucao"));
        logFalhando(false);
        ctx.prontuario.registrar(joao.getId(), null, "Sessao 1");   // usuario repete apos o erro
        assertEquals(1, contar("evolucao"));
        assertTrue(ctx.getConexaoParaTeste().getAutoCommit());
    }

    @Test
    void pacienteNovoSemLogNaoEGravadoEPerdeOId() throws SQLException {
        int antes = contar("paciente");
        Paciente maria = new Paciente("Maria", LocalDate.of(1985, 1, 1), null);
        logFalhando(true);
        assertThrows(DaoException.class, () -> ctx.pacienteService.salvar(maria));
        assertEquals(antes, contar("paciente"));
        assertNull(maria.getId());   // senao a nova tentativa viraria UPDATE de linha inexistente
        logFalhando(false);
        ctx.pacienteService.salvar(maria);
        assertEquals(antes + 1, contar("paciente"));
    }

    @Test
    void agendamentoSemLogNaoEGravado() throws SQLException {
        logFalhando(true);
        assertThrows(DaoException.class, () -> ctx.agenda.agendar(joao.getId(), ana.getId(), sessao.getId(),
                LocalDateTime.of(2026, 10, 20, 10, 0), Modalidade.PRESENCIAL, false));
        assertEquals(0, contar("atendimento"));
    }

    @Test
    void transacaoDesfazTudoEEReentrante() throws SQLException {
        int antes = contar("log_acesso");
        assertThrows(IllegalStateException.class, () -> ctx.transacao(() -> {
            ctx.auditar("TESTE_EXTERNO", null, null);
            ctx.transacao(() -> ctx.auditar("TESTE_INTERNO", null, null));   // aninhada: nao faz commit
            throw new IllegalStateException("falha depois dos logs");
        }));
        assertEquals(antes, contar("log_acesso"));
        assertTrue(ctx.getConexaoParaTeste().getAutoCommit());
        ctx.transacao(() -> ctx.auditar("TESTE_OK", null, null));
        assertEquals(antes + 1, contar("log_acesso"));
    }

    // ---------- M3: registro corrompido nao derruba o prontuario ----------
    @Test
    void registroIlegivelNaoDerrubaALista() {
        ctx.prontuario.registrar(joao.getId(), null, "Registro bom 1");
        int ruim = ctx.evolucoes.inserir(joao.getId(), null, ana.getId(), new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
                11, 12, 13, 14, 15, 16, 17}, new byte[12], null);   // tag GCM invalida
        ctx.prontuario.registrar(joao.getId(), null, "Registro bom 2");

        List<Evolucao> lista = ctx.prontuario.listar(joao.getId());
        assertEquals(3, lista.size());
        assertEquals(ProntuarioService.ilegivel(ruim),
                lista.stream().filter(e -> e.id() == ruim).findFirst().orElseThrow().texto());
        assertEquals("[registro ilegivel #" + ruim + "]", ProntuarioService.ilegivel(ruim));
        assertTrue(lista.stream().anyMatch(e -> e.texto().equals("Registro bom 1")));
        assertTrue(lista.stream().anyMatch(e -> e.texto().equals("Registro bom 2")));

        var logs = ctx.logs.ultimos(5);
        assertTrue(logs.stream().anyMatch(l -> l.acao().equals("REGISTRO_ILEGIVEL")
                && "evolucao".equals(l.entidade()) && Integer.valueOf(ruim).equals(l.entidadeId())));
        assertEquals("LEITURA_PRONTUARIO", logs.get(0).acao());   // leitura auditada por ultimo, apos decifrar
    }

    // ---------- B7: validacoes de paciente ----------
    @Test
    void pacienteNuloOuCamposGrandesSaoRecusados() {
        assertThrows(RegraNegocioException.class, () -> ctx.pacienteService.salvar(null));
        assertThrows(RegraNegocioException.class, () -> ctx.pacienteService.salvar(
                new Paciente("x".repeat(PacienteService.NOME_MAX + 1), null, null)));
        Paciente p = new Paciente("Maria", null, null);
        p.setContatoEmergencia("y".repeat(PacienteService.CONTATO_MAX + 1));
        assertThrows(RegraNegocioException.class, () -> ctx.pacienteService.salvar(p));
    }

    @Test
    void telefoneDoPacienteEValidado() {
        assertThrows(RegraNegocioException.class, () -> ctx.pacienteService.salvar(new Paciente("Maria", null, "123")));
        assertThrows(RegraNegocioException.class, () -> ctx.pacienteService.salvar(new Paciente("Maria", null, "abc defg hijk")));
        Paciente ok = ctx.pacienteService.salvar(new Paciente("Maria", null, " (62) 99999-0000 "));
        assertEquals("(62) 99999-0000", ok.getTelefone());
        Paciente semTelefone = ctx.pacienteService.salvar(new Paciente("Carla", null, "   "));
        assertNull(semTelefone.getTelefone());
    }

    @Test
    void nascimentoMuitoAntigoERecusado() {
        assertThrows(RegraNegocioException.class, () ->
                ctx.pacienteService.salvar(new Paciente("Maria", LocalDate.of(1800, 1, 1), null)));
        assertDoesNotThrow(() -> ctx.pacienteService.salvar(new Paciente("Dona Rosa", LocalDate.of(1930, 1, 1), null)));
    }

    @Test
    void convenioInexistenteDaMensagemClara() {
        Paciente p = new Paciente("Maria", null, null);
        p.setConvenioId(999);
        RegraNegocioException e = assertThrows(RegraNegocioException.class, () -> ctx.pacienteService.salvar(p));
        assertEquals("Convenio nao encontrado.", e.getMessage());
        Convenio c = ctx.convenios.salvar(new Convenio("Unimed", null, 90, 30));
        p.setConvenioId(c.getId());
        assertDoesNotThrow(() -> ctx.pacienteService.salvar(p));
    }

    /** Troca o relogio fixo por um que o teste pode avancar (comeca no mesmo instante). */
    private RelogioAjustavel relogioAjustavel() {
        RelogioAjustavel r = new RelogioAjustavel(ctx.agora(), ZoneId.systemDefault());
        ctx.setRelogio(r);
        return r;
    }

    private static final class RelogioAjustavel extends Clock {
        private Instant agora;
        private final ZoneId zona;

        RelogioAjustavel(Instant agora, ZoneId zona) { this.agora = agora; this.zona = zona; }

        void avancar(Duration d) { agora = agora.plus(d); }

        @Override public ZoneId getZone() { return zona; }
        @Override public Clock withZone(ZoneId z) { return new RelogioAjustavel(agora, z); }
        @Override public Instant instant() { return agora; }
    }
}
