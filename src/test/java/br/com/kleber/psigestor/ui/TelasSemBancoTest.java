package br.com.kleber.psigestor.ui;

import br.com.kleber.psigestor.model.Papel;
import br.com.kleber.psigestor.service.Contexto;
import javafx.application.Platform;
import javafx.scene.Node;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * B2: com o banco inacessivel, nenhuma tela pode lancar excecao na criacao (janela em branco);
 * o erro tem de passar por Ui.executar e virar aviso. Precisa do toolkit JavaFX: sem display, e pulado.
 */
class TelasSemBancoTest {
    private static boolean fxDisponivel;

    @BeforeAll
    static void iniciarJavaFx() throws InterruptedException {
        try {
            CountDownLatch pronto = new CountDownLatch(1);
            Platform.startup(pronto::countDown);
            fxDisponivel = pronto.await(10, TimeUnit.SECONDS);
        } catch (IllegalStateException jaIniciado) {
            fxDisponivel = true;
        } catch (UnsupportedOperationException | UnsatisfiedLinkError semDisplay) {
            fxDisponivel = false;
        }
        if (fxDisponivel) Platform.setImplicitExit(false);
    }

    @Test
    void telasComBancoFechadoAvisamEmVezDeQuebrar(@TempDir Path tmp) throws InterruptedException {
        assumeTrue(fxDisponivel, "toolkit JavaFX indisponivel (ambiente sem display)");
        Contexto ctx = new Contexto("jdbc:sqlite:" + tmp.resolve("teste.db"));
        ctx.autenticacao.cadastrar("Ana Souza", "ana", "senhaForte1".toCharArray(), Papel.PSICOLOGO, "09/12345", 30);
        ctx.autenticacao.entrar("ana", "senhaForte1".toCharArray());
        ctx.close();   // sessao continua em memoria; toda leitura agora lanca DaoException

        Map<String, Supplier<Node>> telas = new LinkedHashMap<>();
        telas.put("Painel", () -> new PainelView(ctx).criar());
        telas.put("Agenda", () -> new AgendaView(ctx, null).criar());
        telas.put("Pacientes", () -> new PacientesView(ctx).criar());
        telas.put("Prontuario", () -> new ProntuarioView(ctx, null).criar());
        telas.put("Financeiro", () -> new FinanceiroView(ctx).criar());
        telas.put("Servicos & Convenios", () -> new CadastrosView(ctx).criar());
        telas.put("Equipe", () -> new EquipeView(ctx).criar());
        telas.put("Privacidade", () -> new PrivacidadeView(ctx).criar());
        telas.put("Tela principal", () -> { new TelaPrincipal(ctx, () -> { }).criar(); return null; });

        List<String> exibidos = new ArrayList<>();
        Consumer<String> original = Ui.exibirErro;
        Ui.exibirErro = exibidos::add;
        try {
            for (var tela : telas.entrySet()) {
                exibidos.clear();
                Throwable erro = noThreadJavaFx(() -> tela.getValue().get());
                assertNull(erro, tela.getKey() + " lancou " + erro);
                assertFalse(exibidos.isEmpty(), tela.getKey() + " nao avisou o erro de banco");
                assertTrue(exibidos.stream().allMatch(Ui.ERRO_GENERICO::equals), tela.getKey() + ": " + exibidos);
            }
        } finally {
            Ui.exibirErro = original;
        }
    }

    private static Throwable noThreadJavaFx(Runnable acao) throws InterruptedException {
        AtomicReference<Throwable> erro = new AtomicReference<>();
        CountDownLatch fim = new CountDownLatch(1);
        Platform.runLater(() -> {
            try { acao.run(); } catch (Throwable t) { erro.set(t); } finally { fim.countDown(); }
        });
        assertTrue(fim.await(20, TimeUnit.SECONDS), "tela travou");
        return erro.get();
    }
}
