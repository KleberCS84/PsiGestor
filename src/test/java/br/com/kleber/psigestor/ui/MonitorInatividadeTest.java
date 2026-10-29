package br.com.kleber.psigestor.ui;

import br.com.kleber.psigestor.model.Papel;
import br.com.kleber.psigestor.service.Contexto;
import br.com.kleber.psigestor.util.SessaoEncerradaException;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * B-12: atividade em dialogo modal renova a sessao; sessao vencida fecha os dialogos e avisa uma vez.
 * Precisa do toolkit JavaFX: sem display, e pulado.
 */
class MonitorInatividadeTest {
    private static boolean fxDisponivel;

    @TempDir Path tmp;
    private Contexto ctx;
    private Stage principal;
    private Stage dialogo;
    private MonitorInatividade monitor;
    private final AtomicInteger expirou = new AtomicInteger();
    private final AtomicInteger teclasNoDialogo = new AtomicInteger();
    private final List<String> exibidos = new ArrayList<>();
    private Consumer<String> exibirOriginal;

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

    @BeforeEach
    void preparar() throws InterruptedException {
        assumeTrue(fxDisponivel, "toolkit JavaFX indisponivel (ambiente sem display)");
        ctx = new Contexto("jdbc:sqlite:" + tmp.resolve("teste.db"));
        ctx.autenticacao.cadastrar("Ana Souza", "ana", "senhaForte12".toCharArray(), Papel.PSICOLOGO, "09/12345", 30);
        ctx.autenticacao.entrar("ana", "senhaForte12".toCharArray());
        exibirOriginal = Ui.exibirErro;
        Ui.exibirErro = exibidos::add;
        Ui.sessaoIniciada();
        noThreadJavaFx(() -> {
            principal = new Stage();
            principal.setScene(new Scene(new StackPane(), 200, 200));
            principal.show();
            monitor = new MonitorInatividade(ctx, principal, () -> false, expirou::incrementAndGet);
            monitor.iniciar();
            // aberto DEPOIS do monitor, como um dialogo de cadastro/backup: tambem tem de ser vigiado
            dialogo = new Stage();
            dialogo.initOwner(principal);
            dialogo.initModality(Modality.WINDOW_MODAL);
            StackPane raiz = new StackPane();
            raiz.addEventHandler(KeyEvent.KEY_PRESSED, e -> teclasNoDialogo.incrementAndGet());
            dialogo.setScene(new Scene(raiz, 100, 100));
            dialogo.show();
        });
    }

    @AfterEach
    void fechar() throws InterruptedException {
        if (!fxDisponivel) return;
        noThreadJavaFx(() -> {
            monitor.parar();
            dialogo.close();
            principal.close();
        });
        Ui.exibirErro = exibirOriginal;
        ctx.close();
    }

    @Test
    void atividadeEmDialogoRenovaSessao() throws InterruptedException {
        Duration antes = ctx.restanteAteExpirar();
        Thread.sleep(1100);                                   // sem atividade, o restante so diminui
        assertTrue(ctx.restanteAteExpirar().compareTo(antes) < 0);
        noThreadJavaFx(this::teclaNoDialogo);
        assertTrue(ctx.restanteAteExpirar().compareTo(antes) > 0,
                "tecla no dialogo modal nao renovou a sessao: " + ctx.restanteAteExpirar());
        assertEquals(1, teclasNoDialogo.get());
        assertEquals(0, expirou.get());
    }

    @Test
    void sessaoVencidaFechaDialogosEAvisaUmaVez() throws InterruptedException {
        ctx.sair();                                            // mesmo efeito da sessao vencida: nao ha mais sessao
        noThreadJavaFx(this::teclaNoDialogo);
        noThreadJavaFx(() -> { });                             // deixa rodar o runLater do aviso
        assertEquals(0, teclasNoDialogo.get(), "o evento depois do limite chegou ao dialogo");
        assertEquals(1, expirou.get());
        assertFalse(dialogo.isShowing(), "dialogo continuou visivel sobre a tela de login");
        assertTrue(principal.isShowing());
        // outra tela que esbarra na sessao vencida logo depois nao repete a mensagem
        noThreadJavaFx(() -> Ui.executar(() -> { throw new SessaoEncerradaException(); }));
        assertEquals(List.of(SessaoEncerradaException.MENSAGEM), exibidos);
    }

    @Test
    void avisoCitaTextoNaoSalvo() {
        assertFalse(MonitorInatividade.textoAviso(false).contains("não salvo"));
        assertTrue(MonitorInatividade.textoAviso(true).contains("1 minuto"));
        assertTrue(MonitorInatividade.textoAviso(true).contains("texto não salvo no prontuário"));
    }

    private void teclaNoDialogo() {
        Event.fireEvent(dialogo.getScene().getRoot(),
                new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.A, false, false, false, false));
    }

    private static void noThreadJavaFx(Runnable acao) throws InterruptedException {
        AtomicReference<Throwable> erro = new AtomicReference<>();
        CountDownLatch fim = new CountDownLatch(1);
        Platform.runLater(() -> {
            try { acao.run(); } catch (Throwable t) { erro.set(t); } finally { fim.countDown(); }
        });
        assertTrue(fim.await(20, TimeUnit.SECONDS), "JavaFX travou");
        if (erro.get() != null) fail(erro.get());
    }
}
