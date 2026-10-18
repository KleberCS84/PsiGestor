package br.com.kleber.psigestor.service;

import br.com.kleber.psigestor.model.*;
import br.com.kleber.psigestor.util.RegraNegocioException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
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

/** Fluxos completos ponta a ponta pelos services, com banco real em arquivo temporario. */
class FluxosIntegracaoTest {

    static final String SENHA_ANA = "senhaForte12";
    static final String SENHA_BIA = "senhaBia1234";
    static final String SENHA_BACKUP = "senhaDoBackup2026";

    @TempDir Path tmp;
    Contexto ctx;

    @AfterEach
    void fechar() { if (ctx != null) ctx.close(); }

    private String url() { return "jdbc:sqlite:" + tmp.resolve("fluxo.db"); }

    private static void fixarRelogio(Contexto c, LocalDate dia) {
        ZoneId zona = ZoneId.systemDefault();
        c.setRelogio(Clock.fixed(dia.atStartOfDay(zona).toInstant(), zona));
    }

    private Contexto abrir() {
        Contexto c = new Contexto(url());
        fixarRelogio(c, LocalDate.of(2026, 10, 12));
        return c;
    }

    private List<String> acoes() {
        return ctx.logs.ultimos(200).stream().map(LogAcesso::acao).toList();
    }

    private long contar(String sql) throws SQLException {
        try (Statement st = ctx.getConexaoParaTeste().createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // ---------- 1. primeiro acesso ate o repasse ----------
    @Test
    void fluxoCompletoDoPrimeiroAcessoAoRepasse() throws SQLException {
        ctx = abrir();
        assertTrue(ctx.autenticacao.primeiroAcesso());
        assertNull(ctx.getSessao());
        assertThrows(RegraNegocioException.class, () -> ctx.cadastros.listarServicos());   // sem sessao nada funciona

        Usuario ana = ctx.autenticacao.cadastrar("Ana Souza", "ana", SENHA_ANA.toCharArray(),
                Papel.PSICOLOGO, "09/12345", 30);
        assertFalse(ctx.autenticacao.primeiroAcesso());
        assertNull(ctx.getSessao());                       // cadastrar nao abre sessao
        ctx.autenticacao.entrar("ana", SENHA_ANA.toCharArray());
        assertNotNull(ctx.getSessao());

        Servico sessao = ctx.cadastros.salvarServico(new Servico("Psicoterapia individual", null, 50, 20000));
        Convenio unimed = ctx.cadastros.salvarConvenio(new Convenio("Unimed Teste", "123456", 9000, 30));
        Paciente joao = ctx.pacienteService.salvar(new Paciente("Joao Lima", LocalDate.of(1990, 5, 20), "62999990000"));
        Paciente maria = new Paciente("Maria Souza", LocalDate.of(1985, 1, 1), "62988880000");
        maria.setConvenioId(unimed.getId());
        maria = ctx.pacienteService.salvar(maria);

        LocalDate dia = LocalDate.of(2026, 10, 20);
        Atendimento particular = ctx.agenda.agendar(joao.getId(), ana.getId(), sessao.getId(),
                dia.atTime(8, 0), Modalidade.PRESENCIAL, false);
        Atendimento conveniado = ctx.agenda.agendar(maria.getId(), ana.getId(), sessao.getId(),
                dia.atTime(9, 0), Modalidade.PRESENCIAL, true);
        assertEquals(20000, particular.getValorCentavos());
        assertEquals(9000, conveniado.getValorCentavos());          // convenio substitui o valor do servico
        assertEquals(StatusAtendimento.AGENDADO, ctx.atendimentos.buscar(particular.getId()).orElseThrow().getStatus());

        // agendado ainda nao fatura
        assertEquals(0, ctx.financeiro.resumo(dia, dia).faturado());

        ctx.agenda.alterarStatus(particular.getId(), StatusAtendimento.REALIZADO);
        ctx.agenda.alterarStatus(conveniado.getId(), StatusAtendimento.REALIZADO);
        ctx.agenda.registrarPagamento(particular.getId(), "PIX");

        FinanceiroService.Resumo r = ctx.financeiro.resumo(dia, dia);
        assertEquals(29000, r.faturado());
        assertEquals(20000, r.recebido());
        assertEquals(9000, r.aReceber());
        assertEquals(r.faturado(), r.recebido() + r.aReceber());
        assertEquals(2, r.realizados());
        assertEquals(1, r.recibosPendentes());                       // so o particular pago exige recibo

        List<FinanceiroService.LinhaRepasse> repasse = ctx.financeiro.repasse(dia, dia);
        assertEquals(1, repasse.size());
        FinanceiroService.LinhaRepasse l = repasse.get(0);
        assertEquals("Ana Souza", l.profissional());
        assertEquals(2, l.sessoes());
        assertEquals(29000, l.bruto());
        assertEquals(8700, l.parteClinica());                        // 30% de R$ 290,00
        assertEquals(20300, l.liquidoProfissional());
        assertEquals(l.bruto(), l.parteClinica() + l.liquidoProfissional());

        // valores gravados no banco como inteiros em centavos
        assertEquals(29000, contar("SELECT SUM(valor) FROM atendimento WHERE status = 'REALIZADO'"));
        assertEquals(1, contar("SELECT count(*) FROM atendimento WHERE pago = 1 AND forma_pagamento = 'PIX'"));

        // trilha de auditoria
        List<String> acoes = acoes();
        assertTrue(acoes.containsAll(List.of("CADASTRO_USUARIO", "LOGIN", "CADASTRO_SERVICO", "CADASTRO_CONVENIO",
                "CADASTRO_PACIENTE", "AGENDAMENTO", "STATUS_AGENDADO->REALIZADO", "PAGAMENTO")), acoes.toString());
        assertEquals(2, acoes.stream().filter(a -> a.equals("AGENDAMENTO")).count());
        assertEquals(2, acoes.stream().filter(a -> a.equals("STATUS_AGENDADO->REALIZADO")).count());
        assertEquals(1, acoes.stream().filter(a -> a.equals("PAGAMENTO")).count());
    }

    @Test
    void secretariaOperaAgendaMasNaoAlteraValores() throws SQLException {
        ctx = abrir();
        Usuario ana = ctx.autenticacao.cadastrar("Ana Souza", "ana", SENHA_ANA.toCharArray(),
                Papel.PSICOLOGO, "09/12345", 30);
        ctx.autenticacao.entrar("ana", SENHA_ANA.toCharArray());
        Servico sessao = ctx.cadastros.salvarServico(new Servico("Psicoterapia individual", null, 50, 20000));
        Paciente joao = ctx.pacienteService.salvar(new Paciente("Joao Lima", LocalDate.of(1990, 5, 20), "62999990000"));
        Usuario carlos = ctx.autenticacao.cadastrar("Carlos Dias", "carlos", "senhaCarlos1".toCharArray(),
                Papel.PSICOLOGO, "09/999", 50);
        ctx.autenticacao.cadastrar("Bia Recepcao", "bia", SENHA_BIA.toCharArray(), Papel.SECRETARIA, null, 0);

        ctx.sair();
        ctx.autenticacao.entrar("bia", SENHA_BIA.toCharArray());

        LocalDate dia = LocalDate.of(2026, 10, 20);
        Atendimento a1 = ctx.agenda.agendar(joao.getId(), ana.getId(), sessao.getId(),
                dia.atTime(8, 0), Modalidade.PRESENCIAL, false);
        Atendimento a2 = ctx.agenda.agendar(joao.getId(), carlos.getId(), sessao.getId(),
                dia.atTime(10, 0), Modalidade.PRESENCIAL, false);
        ctx.agenda.alterarStatus(a1.getId(), StatusAtendimento.REALIZADO);
        ctx.agenda.alterarStatus(a2.getId(), StatusAtendimento.REALIZADO);
        ctx.agenda.registrarPagamento(a1.getId(), "Dinheiro");

        // nada que mexe em valores ou repasse
        assertThrows(RegraNegocioException.class, () ->
                ctx.cadastros.salvarServico(new Servico("Promocao", null, 50, 1)));
        assertThrows(RegraNegocioException.class, () -> ctx.cadastros.atualizarRepasse(ana.getId(), 0));
        assertThrows(RegraNegocioException.class, () -> ctx.prontuario.listar(joao.getId()));
        assertEquals(30, ctx.usuarios.buscar(ana.getId()).orElseThrow().getPercentualRepasseClinica());
        assertEquals(20000, ctx.servicos.buscar(sessao.getId()).orElseThrow().getValorCentavos());

        // a secretaria enxerga o financeiro; repasse de cada profissional usa o percentual do cadastro
        FinanceiroService.Resumo r = ctx.financeiro.resumo(dia, dia);
        assertEquals(40000, r.faturado());
        assertEquals(20000, r.recebido());
        assertEquals(20000, r.aReceber());

        List<FinanceiroService.LinhaRepasse> linhas = ctx.financeiro.repasse(dia, dia);
        assertEquals(2, linhas.size());
        for (FinanceiroService.LinhaRepasse l : linhas) {
            assertEquals(l.bruto(), l.parteClinica() + l.liquidoProfissional());
            assertEquals(20000, l.bruto());
            long esperadoClinica = l.profissional().equals("Ana Souza") ? 6000 : 10000;   // 30% e 50%
            assertEquals(esperadoClinica, l.parteClinica(), l.profissional());
        }
        long totalClinica = linhas.stream().mapToLong(FinanceiroService.LinhaRepasse::parteClinica).sum();
        long totalLiquido = linhas.stream().mapToLong(FinanceiroService.LinhaRepasse::liquidoProfissional).sum();
        assertEquals(r.faturado(), totalClinica + totalLiquido);

        // o log registra quem fez: as acoes da secretaria carregam o login dela
        assertTrue(ctx.logs.ultimos(200).stream()
                .anyMatch(l -> l.acao().equals("PAGAMENTO") && l.usuario() != null && l.usuario().contains("Bia")),
                ctx.logs.ultimos(10).toString());
        assertFalse(acoes().contains("ALTERACAO_REPASSE"));
    }

    // ---------- 2. backup e restauracao ----------
    @Test
    void backupAlteracaoRestauracaoDevolveEstadoDoBackup() throws Exception {
        ctx = abrir();
        Usuario ana = ctx.autenticacao.cadastrar("Ana Souza", "ana", SENHA_ANA.toCharArray(),
                Papel.PSICOLOGO, "09/12345", 30);
        ctx.autenticacao.entrar("ana", SENHA_ANA.toCharArray());
        Servico sessao = ctx.cadastros.salvarServico(new Servico("Psicoterapia individual", null, 50, 20000));
        Paciente joao = ctx.pacienteService.salvar(new Paciente("Joao Lima", LocalDate.of(1990, 5, 20), "62999990000"));
        Atendimento at = ctx.agenda.agendar(joao.getId(), ana.getId(), sessao.getId(),
                LocalDateTime.of(2026, 10, 13, 14, 0), Modalidade.PRESENCIAL, false);
        ctx.agenda.alterarStatus(at.getId(), StatusAtendimento.REALIZADO);
        ctx.prontuario.registrar(joao.getId(), at.getId(), "Sessao inicial: queixa de ansiedade.");

        Path backup = ctx.backup.gerar(tmp.resolve("backups"), SENHA_BACKUP.toCharArray(), SENHA_BACKUP.toCharArray());
        assertTrue(Files.exists(backup));

        // alteracoes posteriores ao backup
        ctx.pacienteService.salvar(new Paciente("Maria Depois", LocalDate.of(1985, 1, 1), "62988880000"));
        ctx.prontuario.registrar(joao.getId(), null, "Registro posterior ao backup");
        ctx.agenda.registrarPagamento(at.getId(), "PIX");
        ctx.cadastros.atualizarRepasse(ana.getId(), 45);
        assertEquals(2, ctx.pacienteService.pesquisar("", true).size());
        assertEquals(2, ctx.prontuario.listar(joao.getId()).size());

        // senha errada: recusada e nada muda (banco aberto, sessao viva, nenhuma copia criada)
        assertThrows(RegraNegocioException.class, () -> ctx.backup.restaurar(backup, "senhaErrada2026".toCharArray()));
        assertNotNull(ctx.getSessao());
        assertEquals(2, ctx.pacienteService.pesquisar("", true).size());
        assertEquals(2, ctx.prontuario.listar(joao.getId()).size());
        try (var arquivos = Files.list(tmp)) {
            assertTrue(arquivos.noneMatch(p -> p.getFileName().toString().contains("antes-restauracao")));
        }

        // senha certa: o banco e substituido e o contexto fica fechado (reinicio)
        Path copia = ctx.backup.restaurar(backup, SENHA_BACKUP.toCharArray());
        assertTrue(Files.exists(copia));
        assertNull(ctx.getSessao());
        ctx = abrir();                                              // "reiniciar o app" na mesma URL

        assertNull(ctx.getSessao());
        ctx.autenticacao.entrar("ana", SENHA_ANA.toCharArray());
        assertEquals(List.of("Joao Lima"),
                ctx.pacienteService.pesquisar("", true).stream().map(Paciente::getNome).toList());
        List<Evolucao> prontuario = ctx.prontuario.listar(joao.getId());
        assertEquals(1, prontuario.size());                         // prontuario legivel com a chave do backup
        assertEquals("Sessao inicial: queixa de ansiedade.", prontuario.get(0).texto());
        Atendimento restaurado = ctx.atendimentos.buscar(at.getId()).orElseThrow();
        assertEquals(StatusAtendimento.REALIZADO, restaurado.getStatus());
        assertFalse(restaurado.isPago());                           // pagamento foi posterior ao backup
        assertEquals(30, ctx.usuarios.buscarPorLogin("ana").orElseThrow().getPercentualRepasseClinica());
        assertEquals(20000, restaurado.getValorCentavos());

        assertTrue(acoes().contains("RESTAURACAO_BACKUP"));
        assertFalse(acoes().contains("BACKUP"));                    // o log do backup e gravado depois da copia
        assertFalse(acoes().contains("ALTERACAO_REPASSE"));         // acao posterior ao backup nao existe mais
    }

    // ---------- 3. anonimizacao ----------
    @Test
    void anonimizacaoRespeitaPrazoPapelEPreservaHistorico() throws SQLException {
        ctx = abrir();
        Usuario ana = ctx.autenticacao.cadastrar("Ana Souza", "ana", SENHA_ANA.toCharArray(),
                Papel.PSICOLOGO, "09/12345", 30);
        ctx.autenticacao.entrar("ana", SENHA_ANA.toCharArray());
        ctx.autenticacao.cadastrar("Bia Recepcao", "bia", SENHA_BIA.toCharArray(), Papel.SECRETARIA, null, 0);
        Servico sessao = ctx.cadastros.salvarServico(new Servico("Psicoterapia individual", null, 50, 20000));
        Paciente joao = new Paciente("Joao Lima", LocalDate.of(1990, 5, 20), "62999990000");
        joao.setCpf("529.982.247-25");
        joao.setEmail("joao@exemplo.com");
        joao.setContatoEmergencia("Maria - 62988887777");
        joao = ctx.pacienteService.salvar(joao);
        final int id = joao.getId();

        Atendimento at = ctx.agenda.agendar(id, ana.getId(), sessao.getId(),
                LocalDateTime.of(2026, 10, 13, 14, 0), Modalidade.PRESENCIAL, false);
        ctx.agenda.alterarStatus(at.getId(), StatusAtendimento.REALIZADO);
        ctx.agenda.registrarPagamento(at.getId(), "PIX");
        ctx.prontuario.registrar(id, at.getId(), "Registro que deve sobreviver a anonimizacao");

        // antes do prazo: recusado, dados intactos, sem log
        RegraNegocioException antes = assertThrows(RegraNegocioException.class,
                () -> ctx.pacienteService.anonimizarPaciente(id));
        assertTrue(antes.getMessage().contains("5 anos"), antes.getMessage());
        assertEquals("Joao Lima", ctx.pacienteService.buscar(id).getNome());
        assertFalse(acoes().contains("ANONIMIZACAO_PACIENTE"));

        // secretaria: recusada mesmo depois do prazo
        fixarRelogio(ctx, LocalDate.of(2032, 1, 10));
        ctx.autenticacao.entrar("bia", SENHA_BIA.toCharArray());
        assertThrows(RegraNegocioException.class, () -> ctx.pacienteService.anonimizarPaciente(id));
        assertEquals("Joao Lima", ctx.pacienteService.buscar(id).getNome());

        // no limite exato (ultimo atendimento + 5 anos) ainda recusado
        ctx.autenticacao.entrar("ana", SENHA_ANA.toCharArray());
        fixarRelogio(ctx, LocalDate.of(2031, 10, 13));
        ctx.autenticacao.entrar("ana", SENHA_ANA.toCharArray());
        assertThrows(RegraNegocioException.class, () -> ctx.pacienteService.anonimizarPaciente(id));

        // depois do prazo: identificadores neutros, historico preservado
        fixarRelogio(ctx, LocalDate.of(2032, 1, 10));
        ctx.autenticacao.entrar("ana", SENHA_ANA.toCharArray());
        ctx.pacienteService.anonimizarPaciente(id);

        Paciente p = ctx.pacienteService.buscar(id);
        assertEquals("Paciente anonimizado #" + id, p.getNome());
        assertNull(p.getCpf());
        assertNull(p.getTelefone());
        assertNull(p.getEmail());
        assertNull(p.getDataNascimento());
        assertNull(p.getContatoEmergencia());

        List<Atendimento> atendimentos = ctx.atendimentos.listarPorPaciente(id);
        assertEquals(1, atendimentos.size());
        assertEquals(StatusAtendimento.REALIZADO, atendimentos.get(0).getStatus());
        assertTrue(atendimentos.get(0).isPago());
        assertEquals(20000, atendimentos.get(0).getValorCentavos());

        LocalDate dia = LocalDate.of(2026, 10, 13);
        FinanceiroService.Resumo r = ctx.financeiro.resumo(dia, dia);
        assertEquals(20000, r.faturado());
        assertEquals(20000, r.recebido());

        assertEquals("Registro que deve sobreviver a anonimizacao", ctx.prontuario.listar(id).get(0).texto());
        assertEquals(1, acoes().stream().filter(a -> a.equals("ANONIMIZACAO_PACIENTE")).count());
        assertEquals(0, contar("SELECT count(*) FROM paciente WHERE nome = 'Joao Lima' OR cpf IS NOT NULL"));
    }
}
