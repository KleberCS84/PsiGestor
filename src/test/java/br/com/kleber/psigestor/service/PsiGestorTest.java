package br.com.kleber.psigestor.service;

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
import java.time.LocalDate;
import java.time.LocalDateTime;
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
}
