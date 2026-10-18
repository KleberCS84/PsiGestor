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
import java.util.Arrays;
import java.util.List;
import javax.crypto.SecretKey;

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
        ana = ctx.autenticacao.cadastrar("Ana Souza", "ana", "senhaForte12".toCharArray(),
                Papel.PSICOLOGO, "09/12345", 30);
        ctx.autenticacao.entrar("ana", "senhaForte12".toCharArray());
        sessao = ctx.servicos.salvar(new Servico("Psicoterapia individual", null, 50, 20000));
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
        assertNotEquals("senhaForte12", u.getSenhaHash());
    }

    // ---------- troca de senha (criptografia de envelope) ----------
    @Test
    void trocaDeSenhaMantemProntuarioLegivel() {
        ctx.prontuario.registrar(joao.getId(), null, "Registro antes da troca");
        ctx.autenticacao.trocarSenha("senhaForte12".toCharArray(), "novaSenha222".toCharArray());
        ctx.sair();
        assertThrows(RegraNegocioException.class, () -> ctx.autenticacao.entrar("ana", "senhaForte12".toCharArray()));
        ctx.autenticacao.entrar("ana", "novaSenha222".toCharArray());
        assertEquals("Registro antes da troca", ctx.prontuario.listar(joao.getId()).get(0).texto());
        assertTrue(ctx.logs.ultimos(10).stream().anyMatch(l -> l.acao().equals("TROCA_SENHA")));
    }

    @Test
    void trocaDeSenhaExigeSenhaAtualCorreta() {
        assertThrows(RegraNegocioException.class, () ->
                ctx.autenticacao.trocarSenha("chuteErrado".toCharArray(), "novaSenha222".toCharArray()));
        assertThrows(RegraNegocioException.class, () ->        // nova senha curta demais
                ctx.autenticacao.trocarSenha("senhaForte12".toCharArray(), "curta".toCharArray()));
        ctx.sair();
        assertDoesNotThrow(() -> ctx.autenticacao.entrar("ana", "senhaForte12".toCharArray()));
    }

    /** Simula o banco real criado antes desta versao: sem as colunas novas e prontuario com a chave antiga. */
    @Test
    void bancoAntigoEMigradoSemPerderProntuario() throws SQLException {
        Usuario u = ctx.usuarios.buscarPorLogin("ana").orElseThrow();
        var chaveAntiga = Criptografia.derivarChave("senhaForte12".toCharArray(), u.getChaveSalt(), u.getIteracoes());
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
        ctx.autenticacao.entrar("ana", "senhaForte12".toCharArray()); // e aqui a chave e embrulhada
        assertNotNull(ctx.usuarios.buscarPorLogin("ana").orElseThrow().getChaveCifrada());
        assertEquals("Registro do MVP", ctx.prontuario.listar(joao.getId()).get(0).texto());

        ctx.autenticacao.trocarSenha("senhaForte12".toCharArray(), "novaSenha222".toCharArray());
        ctx.sair();
        ctx.autenticacao.entrar("ana", "novaSenha222".toCharArray());
        assertEquals("Registro do MVP", ctx.prontuario.listar(joao.getId()).get(0).texto());
    }

    /** M5: usuario anterior a v4 (210.000 iteracoes) entra com a senha antiga e e migrado sem recifrar nada. */
    @Test
    void usuarioCom210milIteracoesEMigradoNoLogin() throws SQLException {
        ctx.prontuario.registrar(joao.getId(), null, "Registro com 210 mil");
        // Ana volta ao formato antigo: mesma DEK, hash da senha e KEK com 210.000 iteracoes
        Usuario u = ctx.usuarios.buscarPorLogin("ana").orElseThrow();
        SecretKey dek = ctx.getSessao().chaveProntuario();
        char[] senha = "senhaForte12".toCharArray();
        u.setIteracoes(Criptografia.ITERACOES_LEGADO);
        u.setSenhaHash(Criptografia.hashSenha(senha, u.getSenhaSalt(), Criptografia.ITERACOES_LEGADO));
        byte[][] embrulho = Criptografia.embrulharChave(dek,
                Criptografia.derivarChave(senha, u.getChaveSalt(), Criptografia.ITERACOES_LEGADO));
        u.setChaveIv(embrulho[0]);
        u.setChaveCifrada(embrulho[1]);
        ctx.usuarios.atualizarCredenciais(u);
        byte[] cifradoAntes = conteudoCifrado();
        ctx.close();

        String url = "jdbc:sqlite:" + tmp.resolve("teste.db");
        try (Connection con = DriverManager.getConnection(url); Statement st = con.createStatement()) {
            st.execute("ALTER TABLE usuario DROP COLUMN iteracoes");
            st.execute("PRAGMA user_version = 3");
        }
        ctx = new Contexto(url);                                   // migracao v4: existentes = 210000
        Usuario antes = ctx.usuarios.buscarPorLogin("ana").orElseThrow();
        assertEquals(Criptografia.ITERACOES_LEGADO, antes.getIteracoes());

        ctx.autenticacao.entrar("ana", "senhaForte12".toCharArray());
        Usuario depois = ctx.usuarios.buscarPorLogin("ana").orElseThrow();
        assertEquals(Criptografia.ITERACOES_ATUAL, depois.getIteracoes());
        assertNotEquals(antes.getSenhaHash(), depois.getSenhaHash());
        assertFalse(Arrays.equals(antes.getChaveCifrada(), depois.getChaveCifrada()));   // reembrulhada
        assertTrue(ctx.logs.ultimos(5).stream().anyMatch(l -> l.acao().equals("MIGRACAO_ITERACOES_SENHA")));
        assertArrayEquals(cifradoAntes, conteudoCifrado());                               // nao recifrou
        assertEquals("Registro com 210 mil", ctx.prontuario.listar(joao.getId()).get(0).texto());

        ctx.sair();
        ctx.autenticacao.entrar("ana", "senhaForte12".toCharArray());
        assertEquals("Registro com 210 mil", ctx.prontuario.listar(joao.getId()).get(0).texto());
    }

    private byte[] conteudoCifrado() throws SQLException {
        try (Statement st = ctx.getConexaoParaTeste().createStatement();
             ResultSet rs = st.executeQuery("SELECT conteudo_cifrado FROM evolucao ORDER BY id")) {
            rs.next();
            return rs.getBytes(1);
        }
    }

    // ---------- anonimizacao (M8) ----------
    @Test
    void anonimizacaoRecusadaAntesDe5Anos() {
        ctx.agenda.agendar(joao.getId(), ana.getId(), sessao.getId(), LocalDateTime.of(2026, 10, 13, 14, 0),
                Modalidade.PRESENCIAL, false);
        var e = assertThrows(RegraNegocioException.class, () -> ctx.pacienteService.anonimizarPaciente(joao.getId()));
        assertTrue(e.getMessage().contains("5 anos"));
        Paciente p = ctx.pacienteService.buscar(joao.getId());
        assertEquals("Joao Lima", p.getNome());
        assertNotNull(p.getTelefone());
        assertNotNull(p.getDataNascimento());
        assertTrue(ctx.logs.ultimos(5).stream().noneMatch(l -> l.acao().equals("ANONIMIZACAO_PACIENTE")));
    }

    @Test
    void anonimizacaoApos5AnosMantemProntuarioEAtendimentos() {
        ctx.prontuario.registrar(joao.getId(), null, "Registro preservado");
        ctx.agenda.agendar(joao.getId(), ana.getId(), sessao.getId(), LocalDateTime.of(2026, 10, 13, 14, 0),
                Modalidade.PRESENCIAL, false);
        ctx.autenticacao.cadastrar("Bia Recepcao", "bia", "senhaBia1234".toCharArray(), Papel.SECRETARIA, null, 0);
        ZoneId zona = ZoneId.systemDefault();
        ctx.setRelogio(Clock.fixed(LocalDate.of(2032, 1, 10).atStartOfDay(zona).toInstant(), zona));

        ctx.autenticacao.entrar("bia", "senhaBia1234".toCharArray());
        assertThrows(RegraNegocioException.class, () -> ctx.pacienteService.anonimizarPaciente(joao.getId()));

        ctx.autenticacao.entrar("ana", "senhaForte12".toCharArray());
        ctx.pacienteService.anonimizarPaciente(joao.getId());
        Paciente p = ctx.pacienteService.buscar(joao.getId());
        assertEquals("Paciente anonimizado #" + joao.getId(), p.getNome());
        assertNull(p.getCpf());
        assertNull(p.getTelefone());
        assertNull(p.getEmail());
        assertNull(p.getDataNascimento());
        assertNull(p.getContatoEmergencia());
        assertEquals(1, ctx.atendimentos.listarPorPaciente(joao.getId()).size());
        assertEquals("Registro preservado", ctx.prontuario.listar(joao.getId()).get(0).texto());
        assertTrue(ctx.logs.ultimos(5).stream().anyMatch(l -> l.acao().equals("ANONIMIZACAO_PACIENTE")));
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
        ctx.autenticacao.cadastrar("Bia Recepcao", "bia", "senhaBia1234".toCharArray(), Papel.SECRETARIA, null, 0);
        ctx.autenticacao.entrar("bia", "senhaBia1234".toCharArray());
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
        Convenio c = ctx.convenios.salvar(new Convenio("Unimed Teste", "123456", 9000, 30));
        joao.setConvenioId(c.getId());
        ctx.pacienteService.salvar(joao);
        Atendimento a = ctx.agenda.agendar(joao.getId(), ana.getId(), sessao.getId(),
                LocalDateTime.of(2026, 10, 15, 8, 0), Modalidade.PRESENCIAL, true);
        assertEquals(9000, a.getValorCentavos());
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
        Convenio fantasma = new Convenio("Fantasma", null, 9000, 30);
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
        assertThrows(DaoException.class, () -> ctx.convenios.salvar(new Convenio("Negativo", null, 9000, -1)));
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
        assertEquals(40000, r.faturado());
        assertEquals(20000, r.recebido());
        assertEquals(20000, r.aReceber());
        assertEquals(1, r.faltas());
        assertEquals(1, r.recibosPendentes());

        FinanceiroService.LinhaRepasse l = ctx.financeiro.repasse(dia, dia).get(0);
        assertEquals(12000, l.parteClinica());     // 30% de R$ 400,00
        assertEquals(28000, l.liquidoProfissional());
    }

    /** M7: em double, 33.33 + 33.33 + 33.33 nao da 99.99 exato; em centavos da. */
    @Test
    void tresSessoesDe3333Somam9999() {
        Servico curta = ctx.servicos.salvar(new Servico("Sessao curta", null, 50, 3333));
        LocalDate dia = LocalDate.of(2026, 10, 21);
        for (int hora = 8; hora <= 10; hora++) {
            Atendimento a = ctx.agenda.agendar(joao.getId(), ana.getId(), curta.getId(), dia.atTime(hora, 0), Modalidade.PRESENCIAL, false);
            ctx.agenda.alterarStatus(a.getId(), StatusAtendimento.REALIZADO);
        }
        assertEquals(9999, ctx.financeiro.resumo(dia, dia).faturado());   // R$ 99,99

        FinanceiroService.LinhaRepasse l = ctx.financeiro.repasse(dia, dia).get(0);
        assertEquals(9999, l.bruto());
        assertEquals(3000, l.parteClinica());                               // 30% = 2999,7 centavos
        assertEquals(6999, l.liquidoProfissional());
        assertEquals(l.bruto(), l.parteClinica() + l.liquidoProfissional());
    }

    /** M7: meio centavo arredonda para o par (HALF_EVEN), nem sempre para cima. */
    @Test
    void repasseArredondaMeioCentavoParaOPar() {
        assertEquals(5000, FinanceiroService.parteClinica(9999, 50));   // 4999,5 -> 5000
        assertEquals(4998, FinanceiroService.parteClinica(9997, 50));   // 4998,5 -> 4998
        assertEquals(0, FinanceiroService.parteClinica(1, 50));         // 0,5 -> 0
        assertEquals(750, FinanceiroService.parteClinica(10000, 7.5));
    }

    /** M7: banco v4 (REAL, em reais) vira INTEGER em centavos sem perder vinculos. */
    @Test
    void migracaoV5ConverteReaisParaCentavos() throws SQLException {
        int servicoId = sessao.getId();
        ctx.close();

        String url = "jdbc:sqlite:" + tmp.resolve("teste.db");
        try (Connection con = DriverManager.getConnection(url); Statement st = con.createStatement()) {
            st.execute("DROP TABLE atendimento");
            st.execute("DROP TABLE servico");
            st.execute("DROP TABLE convenio");
            st.execute("""
                CREATE TABLE convenio (id INTEGER PRIMARY KEY AUTOINCREMENT, nome TEXT NOT NULL UNIQUE,
                    registro_ans TEXT, valor_sessao REAL NOT NULL DEFAULT 0 CHECK (valor_sessao >= 0),
                    prazo_pagamento_dias INTEGER NOT NULL DEFAULT 30)""");
            st.execute("""
                CREATE TABLE servico (id INTEGER PRIMARY KEY AUTOINCREMENT, nome TEXT NOT NULL, codigo_tuss TEXT,
                    duracao_min INTEGER NOT NULL DEFAULT 50 CHECK (duracao_min > 0),
                    valor REAL NOT NULL DEFAULT 0 CHECK (valor >= 0))""");
            st.execute("""
                CREATE TABLE atendimento (id INTEGER PRIMARY KEY AUTOINCREMENT,
                    paciente_id INTEGER NOT NULL REFERENCES paciente(id) ON DELETE RESTRICT,
                    profissional_id INTEGER NOT NULL REFERENCES usuario(id) ON DELETE RESTRICT,
                    servico_id INTEGER NOT NULL REFERENCES servico(id) ON DELETE RESTRICT,
                    convenio_id INTEGER REFERENCES convenio(id) ON DELETE SET NULL,
                    inicio TEXT NOT NULL, fim TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'AGENDADO',
                    modalidade TEXT NOT NULL DEFAULT 'PRESENCIAL', valor REAL NOT NULL DEFAULT 0,
                    pago INTEGER NOT NULL DEFAULT 0, forma_pagamento TEXT,
                    recibo_emitido INTEGER NOT NULL DEFAULT 0, CHECK (fim > inicio))""");
            st.execute("INSERT INTO convenio (id, nome, valor_sessao) VALUES (7, 'Unimed', 80.5)");
            st.execute("INSERT INTO servico (id, nome, valor) VALUES (" + servicoId + ", 'Psicoterapia', 33.33)");
            st.execute("INSERT INTO atendimento (paciente_id, profissional_id, servico_id, convenio_id, inicio, fim, valor) "
                    + "VALUES (" + joao.getId() + ", " + ana.getId() + ", " + servicoId + ", 7, "
                    + "'2026-10-20T08:00', '2026-10-20T08:50', 150.0)");
            st.execute("PRAGMA user_version = 4");
        }

        ctx = new Contexto(url);                                   // migracao v5 roda aqui
        Connection c = ctx.getConexaoParaTeste();
        assertEquals(3333, ctx.servicos.buscar(servicoId).orElseThrow().getValorCentavos());
        assertEquals(8050, ctx.convenios.listar().get(0).getValorSessaoCentavos());
        try (Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT valor, typeof(valor), servico_id, convenio_id FROM atendimento")) {
                assertTrue(rs.next());
                assertEquals(15000, rs.getLong(1));
                assertEquals("integer", rs.getString(2));
                assertEquals(servicoId, rs.getInt(3));
                assertEquals(7, rs.getInt(4));
            }
            for (String[] tc : new String[][] {{"servico", "valor"}, {"convenio", "valor_sessao"}, {"atendimento", "valor"}}) {
                try (ResultSet rs = st.executeQuery("SELECT type FROM pragma_table_info('" + tc[0] + "') WHERE name = '" + tc[1] + "'")) {
                    assertTrue(rs.next());
                    assertEquals("INTEGER", rs.getString(1), tc[0] + "." + tc[1]);
                }
            }
            try (ResultSet rs = st.executeQuery("PRAGMA foreign_key_check")) { assertFalse(rs.next()); }
            try (ResultSet rs = st.executeQuery("PRAGMA foreign_keys")) { assertTrue(rs.next()); assertEquals(1, rs.getInt(1)); }
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM sqlite_master WHERE name = 'idx_atendimento_inicio'")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
        }
        // tabela recriada pelo schema.sql: o CHECK do prazo substitui as triggers da v2
        assertThrows(DaoException.class, () -> ctx.convenios.salvar(new Convenio("Negativo", null, 100, -1)));
    }

    // ---------- B5 / B9 ----------
    /** B5: sem permissao, nem a existencia do login e revelada. */
    @Test
    void cadastrarSemSessaoNaoRevelaLoginExistente() {
        ctx.sair();
        RegraNegocioException e = assertThrows(RegraNegocioException.class, () -> ctx.autenticacao.cadastrar(
                "Outra Ana", "ana", "senhaForte12".toCharArray(), Papel.SECRETARIA, null, 0));
        assertFalse(e.getMessage().contains("Ja existe"), e.getMessage());
    }

    /** B9: % e _ digitados na busca sao texto literal, nao curinga. */
    @Test
    void pesquisaTrataPercentualESublinhadoComoTexto() {
        ctx.pacienteService.salvar(new Paciente("Maria_Clara", LocalDate.of(1990, 5, 20), "62999990001"));
        ctx.pacienteService.salvar(new Paciente("Maria Clara", LocalDate.of(1990, 5, 20), "62999990002"));
        assertEquals(List.of("Maria_Clara"),
                ctx.pacienteService.pesquisar("_", true).stream().map(Paciente::getNome).toList());
        assertEquals(List.of("Maria_Clara"),
                ctx.pacienteService.pesquisar("a_C", true).stream().map(Paciente::getNome).toList());
        assertTrue(ctx.pacienteService.pesquisar("%", true).isEmpty());
        assertTrue(ctx.pacienteService.pesquisar("\\", true).isEmpty());
        assertEquals(3, ctx.pacienteService.pesquisar("", true).size());
    }

    @Test
    void exportacaoSoPsicologoENuncaSobrescreve() {
        Path a1 = ctx.pacienteService.exportarDadosDoTitular(joao.getId(), tmp.resolve("export"));
        Path a2 = ctx.pacienteService.exportarDadosDoTitular(joao.getId(), tmp.resolve("export"));
        assertNotEquals(a1, a2);
        assertTrue(Files.exists(a1) && Files.exists(a2));
        assertFalse(a1.getFileName().toString().contains("_" + joao.getId() + "."));

        ctx.autenticacao.cadastrar("Bia Recepcao", "bia", "senhaBia1234".toCharArray(), Papel.SECRETARIA, null, 0);
        ctx.autenticacao.entrar("bia", "senhaBia1234".toCharArray());
        assertThrows(RegraNegocioException.class,
                () -> ctx.pacienteService.exportarDadosDoTitular(joao.getId(), tmp.resolve("export")));
    }

    // ---------- A5/B4: backup cifrado e restauracao ----------
    static final String SENHA_BACKUP = "senhaDoBackup2026";

    Path gerarBackup() {
        return ctx.backup.gerar(tmp.resolve("backups"), SENHA_BACKUP.toCharArray(), SENHA_BACKUP.toCharArray());
    }

    @Test
    void backupGeraArquivoCifrado() throws Exception {
        Path b = gerarBackup();
        assertTrue(b.getFileName().toString().endsWith(".psgb"));
        byte[] bytes = Files.readAllBytes(b);
        assertEquals("PSGB", new String(bytes, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
        assertFalse(new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1).contains("Joao Lima"));
        assertNotEquals(b, gerarBackup());   // mesmo segundo: nome diferente, nada sobrescrito
        try (var arquivos = Files.list(tmp)) {
            assertTrue(arquivos.noneMatch(p -> p.getFileName().toString().startsWith("backup-")), "temporario ficou");
        }
    }

    @Test
    void senhaDeBackupCurtaOuDiferenteERecusada() {
        Path pasta = tmp.resolve("backups");
        assertThrows(RegraNegocioException.class,
                () -> ctx.backup.gerar(pasta, "curta123".toCharArray(), "curta123".toCharArray()));
        assertThrows(RegraNegocioException.class,
                () -> ctx.backup.gerar(pasta, SENHA_BACKUP.toCharArray(), "outraSenha2026".toCharArray()));
        assertFalse(Files.exists(pasta) && pasta.toFile().list().length > 0);
    }

    @Test
    void secretariaNaoFazBackupNemRestaura() {
        Path b = gerarBackup();
        ctx.autenticacao.cadastrar("Bia Recepcao", "bia", "senhaBia1234".toCharArray(), Papel.SECRETARIA, null, 0);
        ctx.autenticacao.entrar("bia", "senhaBia1234".toCharArray());
        assertThrows(RegraNegocioException.class, this::gerarBackup);
        assertThrows(RegraNegocioException.class, () -> ctx.backup.restaurar(b, SENHA_BACKUP.toCharArray()));
    }

    @Test
    void backupERestauracaoComSenhaCertaPreservaPacientesEProntuario() throws Exception {
        ctx.prontuario.registrar(joao.getId(), null, "Registro antes do backup");
        Path b = gerarBackup();
        ctx.pacienteService.salvar(new Paciente("Maria Depois", LocalDate.of(1985, 1, 1), "62988880000"));

        Path copia = ctx.backup.restaurar(b, SENHA_BACKUP.toCharArray());
        assertTrue(Files.exists(copia));
        assertTrue(copia.getFileName().toString().startsWith("teste.db.antes-restauracao-"));

        ctx = new Contexto("jdbc:sqlite:" + tmp.resolve("teste.db"));   // "reiniciar o app"
        ctx.autenticacao.entrar("ana", "senhaForte12".toCharArray());
        List<String> nomes = ctx.pacienteService.pesquisar("", true).stream().map(Paciente::getNome).toList();
        assertEquals(List.of("Joao Lima"), nomes);
        assertEquals("Registro antes do backup", ctx.prontuario.listar(joao.getId()).get(0).texto());
        assertTrue(ctx.logs.ultimos(10).stream().anyMatch(l -> l.acao().equals("RESTAURACAO_BACKUP")));
        // a copia do banco anterior ainda tem a Maria
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + copia);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM paciente WHERE nome = 'Maria Depois'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void restauracaoComSenhaErradaNaoAlteraBancoAtual() throws Exception {
        Path b = gerarBackup();
        ctx.pacienteService.salvar(new Paciente("Maria Depois", LocalDate.of(1985, 1, 1), "62988880000"));

        assertThrows(RegraNegocioException.class, () -> ctx.backup.restaurar(b, "senhaErrada2026".toCharArray()));
        assertEquals(2, ctx.pacienteService.pesquisar("", true).size());   // banco aberto e intacto
        assertNotNull(ctx.getSessao());
        assertNenhumaCopiaAntesRestauracao();
    }

    @Test
    void backupCorrompidoERecusado() throws Exception {
        Path b = gerarBackup();
        byte[] bytes = Files.readAllBytes(b);

        byte[] meio = bytes.clone();
        meio[meio.length / 2] ^= 0x01;
        Path corrompido = tmp.resolve("corrompido.psgb");
        Files.write(corrompido, meio);
        assertThrows(RegraNegocioException.class, () -> ctx.backup.restaurar(corrompido, SENHA_BACKUP.toCharArray()));

        byte[] cabecalho = bytes.clone();
        cabecalho[0] = 'X';
        Path semMagico = tmp.resolve("sem_magico.psgb");
        Files.write(semMagico, cabecalho);
        assertThrows(RegraNegocioException.class, () -> ctx.backup.restaurar(semMagico, SENHA_BACKUP.toCharArray()));

        Path truncado = tmp.resolve("truncado.psgb");
        Files.write(truncado, java.util.Arrays.copyOf(bytes, 20));
        assertThrows(RegraNegocioException.class, () -> ctx.backup.restaurar(truncado, SENHA_BACKUP.toCharArray()));

        assertEquals(1, ctx.pacienteService.pesquisar("", true).size());
        assertNenhumaCopiaAntesRestauracao();
    }

    @Test
    void backupCifradoQueNaoEBancoERecusado() throws Exception {
        Path falso = tmp.resolve("falso.psgb");
        Files.write(falso, Criptografia.cifrarComSenha("nao sou um banco".getBytes(), SENHA_BACKUP.toCharArray()));
        assertThrows(RegraNegocioException.class, () -> ctx.backup.restaurar(falso, SENHA_BACKUP.toCharArray()));
        assertEquals(1, ctx.pacienteService.pesquisar("", true).size());
        assertNenhumaCopiaAntesRestauracao();
    }

    private void assertNenhumaCopiaAntesRestauracao() throws Exception {
        try (var arquivos = Files.list(tmp)) {
            assertTrue(arquivos.noneMatch(p -> p.getFileName().toString().contains("antes-restauracao")));
        }
    }

    // ---------- A1: servicos, convenios e repasse via service ----------
    @Test
    void secretariaNaoAlteraServicoConvenioNemRepasse() {
        ctx.autenticacao.cadastrar("Bia Recepcao", "bia", "senhaBia1234".toCharArray(), Papel.SECRETARIA, null, 0);
        ctx.autenticacao.entrar("bia", "senhaBia1234".toCharArray());
        assertThrows(RegraNegocioException.class, () ->
                ctx.cadastros.salvarServico(new Servico("Avaliacao", null, 50, 1000)));
        assertThrows(RegraNegocioException.class, () ->
                ctx.cadastros.salvarConvenio(new Convenio("Barato", null, 100, 30)));
        assertThrows(RegraNegocioException.class, () -> ctx.cadastros.atualizarRepasse(ana.getId(), 0));
        assertFalse(ctx.cadastros.listarServicos().isEmpty());   // ver continua permitido
    }

    @Test
    void psicologoAlteraCadastrosComAuditoria() {
        ctx.cadastros.salvarServico(new Servico("Avaliacao", "50000470", 50, 25000));
        Convenio c = ctx.cadastros.salvarConvenio(new Convenio("Unimed Teste", "123456", 9000, 30));
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
                ctx.cadastros.salvarServico(new Servico("Avaliacao", null, 50, 1000)));
        assertThrows(RegraNegocioException.class, () -> ctx.cadastros.listarConvenios());
    }

    // ---------- A2: desativar usuario ----------
    @Test
    void psicologoDesativaUsuarioSemApagar() {
        Usuario bia = ctx.autenticacao.cadastrar("Bia Recepcao", "bia", "senhaBia1234".toCharArray(), Papel.SECRETARIA, null, 0);
        ctx.autenticacao.desativar(bia.getId());
        assertTrue(ctx.logs.ultimos(5).stream().anyMatch(l -> l.acao().equals("DESATIVACAO_USUARIO")));
        assertFalse(ctx.usuarios.buscar(bia.getId()).orElseThrow().isAtivo());   // registro continua no banco
        assertThrows(RegraNegocioException.class, () -> ctx.autenticacao.desativar(bia.getId()));   // ja inativo
        ctx.sair();
        assertThrows(RegraNegocioException.class, () -> ctx.autenticacao.entrar("bia", "senhaBia1234".toCharArray()));
    }

    @Test
    void secretariaNaoDesativaNinguem() {
        ctx.autenticacao.cadastrar("Bia Recepcao", "bia", "senhaBia1234".toCharArray(), Papel.SECRETARIA, null, 0);
        ctx.autenticacao.entrar("bia", "senhaBia1234".toCharArray());
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
        var e = assertThrows(RegraNegocioException.class, () -> ctx.autenticacao.entrar("ana", "senhaForte12".toCharArray()));
        assertTrue(e.getMessage().contains("Muitas tentativas"));
        assertTrue(ctx.logs.ultimos(3).stream().anyMatch(l -> l.acao().equals("LOGIN_BLOQUEADO")));
        r.avancar(Duration.ofSeconds(30));
        assertDoesNotThrow(() -> ctx.autenticacao.entrar("ana", "senhaForte12".toCharArray()));
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
        assertThrows(RegraNegocioException.class, () -> ctx.autenticacao.entrar("ana", "senhaForte12".toCharArray()));
        r.avancar(Duration.ofSeconds(30));
        assertDoesNotThrow(() -> ctx.autenticacao.entrar("ana", "senhaForte12".toCharArray()));
    }

    @Test
    void loginCertoZeraContadorDeFalhas() {
        ctx.sair();
        for (int i = 0; i < 4; i++)
            assertThrows(RegraNegocioException.class, () -> ctx.autenticacao.entrar("ana", "errada123".toCharArray()));
        ctx.autenticacao.entrar("ana", "senhaForte12".toCharArray());
        ctx.sair();
        for (int i = 0; i < 4; i++)
            assertThrows(RegraNegocioException.class, () -> ctx.autenticacao.entrar("ana", "errada123".toCharArray()));
        assertDoesNotThrow(() -> ctx.autenticacao.entrar("ana", "senhaForte12".toCharArray()));
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
        assertThrows(RegraNegocioException.class, () -> ctx.autenticacao.entrar("senhaForte12", "x".toCharArray()));
        var l = ctx.logs.ultimos(1).get(0);
        assertEquals("login inexistente", l.detalhe());
        assertFalse(l.detalhe().contains("senhaForte12"));
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
        Convenio c = ctx.convenios.salvar(new Convenio("Unimed", null, 9000, 30));
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
