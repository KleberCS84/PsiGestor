package br.com.kleber.psigestor.ui;

import br.com.kleber.psigestor.service.Contexto;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.ListChangeListener;
import javafx.event.EventHandler;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.input.InputEvent;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Logout por inatividade na interface (M1, B-12):
 *  - tecla/clique em QUALQUER janela do app (dialogos modais inclusive) renova a sessao;
 *  - no ultimo minuto, aviso nao modal com "Continuar conectado";
 *  - ao vencer, o evento que chegou depois do limite nao e repassado, as outras janelas fecham
 *    e a tela volta ao login com uma unica mensagem "Sessao encerrada".
 */
public class MonitorInatividade {
    static final Duration AVISO_ANTES = Duration.ofSeconds(60);
    static final String TEXTO_AVISO = "Sua sessão será encerrada em 1 minuto por inatividade.";
    static final String TEXTO_NAO_SALVO = "Há texto não salvo no prontuário, que será perdido. Salve antes de sair.";

    private static final ButtonType CONTINUAR = new ButtonType("Continuar conectado", ButtonBar.ButtonData.OK_DONE);

    private final Contexto ctx;
    private final Window principal;
    private final BooleanSupplier textoNaoSalvo;
    private final Runnable aoExpirar;
    private final EventHandler<InputEvent> filtro = this::aoEvento;
    private final List<Window> vigiadas = new ArrayList<>();
    private final ListChangeListener<Window> novasJanelas = mudanca -> {
        while (mudanca.next()) {
            mudanca.getAddedSubList().forEach(this::vigiar);
            mudanca.getRemoved().forEach(this::esquecer);
        }
    };
    private Timeline relogio;
    private Alert aviso;
    private boolean encerrado;

    /** aoExpirar: volta ao login (o monitor ja parou e fechou as demais janelas). */
    public MonitorInatividade(Contexto ctx, Window principal, BooleanSupplier textoNaoSalvo, Runnable aoExpirar) {
        this.ctx = ctx;
        this.principal = principal;
        this.textoNaoSalvo = textoNaoSalvo;
        this.aoExpirar = aoExpirar;
    }

    public void iniciar() {
        new ArrayList<>(Window.getWindows()).forEach(this::vigiar);
        Window.getWindows().addListener(novasJanelas);
        relogio = new Timeline(new KeyFrame(javafx.util.Duration.seconds(1), e -> verificar()));
        relogio.setCycleCount(Animation.INDEFINITE);
        relogio.play();
    }

    /** Logout manual ou expiracao: a tela de login (mesma janela principal) nao e vigiada. */
    public void parar() {
        if (relogio != null) relogio.stop();
        relogio = null;
        Window.getWindows().removeListener(novasJanelas);
        new ArrayList<>(vigiadas).forEach(this::esquecer);
        fecharAviso();
    }

    // filtro na Window: o evento passa por ela antes de chegar a cena e aos controles
    private void vigiar(Window w) {
        if (vigiadas.contains(w) || (aviso != null && w == aviso.getDialogPane().getScene().getWindow())) return;
        w.addEventFilter(InputEvent.ANY, filtro);
        vigiadas.add(w);
    }

    private void esquecer(Window w) {
        w.removeEventFilter(InputEvent.ANY, filtro);
        vigiadas.remove(w);
    }

    /** Pacote: o teste dispara eventos direto aqui e na janela. */
    void aoEvento(InputEvent e) {
        if (encerrado) { e.consume(); return; }
        if (!ctx.registrarAtividade()) {
            e.consume();   // o clique depois do limite nao chega ao botao (seria um NPE/erro generico)
            expirar();
            return;
        }
        fecharAviso();
    }

    void verificar() {
        if (encerrado) return;
        if (ctx.getSessao() == null || ctx.sairSeInativo()) { expirar(); return; }
        if (ctx.restanteAteExpirar().compareTo(AVISO_ANTES) <= 0) mostrarAviso();
    }

    private void mostrarAviso() {
        if (aviso != null) return;
        String texto = textoAviso(textoNaoSalvo.getAsBoolean());
        aviso = new Alert(Alert.AlertType.WARNING, texto, CONTINUAR);
        aviso.setTitle("PsiGestor");
        aviso.setHeaderText(null);
        aviso.initModality(Modality.NONE);
        aviso.initOwner(principal);
        aviso.setOnHidden(e -> {
            // "Continuar conectado" e a resposta de quem esta na frente do computador
            if (!encerrado && aviso != null && aviso.getResult() == CONTINUAR && !ctx.registrarAtividade()) expirar();
            aviso = null;
        });
        aviso.show();   // nao modal: nao bloqueia a tela de quem esta digitando
    }

    static String textoAviso(boolean textoNaoSalvo) {
        return textoNaoSalvo ? TEXTO_AVISO + "\n\n" + TEXTO_NAO_SALVO : TEXTO_AVISO;
    }

    private void fecharAviso() {
        if (aviso == null) return;
        Alert a = aviso;
        aviso = null;   // fechado por atividade em outra janela: a atividade ja foi registrada
        a.close();
    }

    private void expirar() {
        if (encerrado) return;
        encerrado = true;
        parar();
        // dialogos abertos (cadastro, backup, retificacao) nao ficam visiveis sobre a tela de login
        for (Window w : new ArrayList<>(Window.getWindows())) {
            if (w == principal) continue;
            if (w instanceof Stage s) s.close(); else w.hide();
        }
        aoExpirar.run();
        // dialogo modal nao pode abrir dentro do tratador da animacao
        javafx.application.Platform.runLater(Ui::avisarSessaoEncerrada);
    }

    boolean encerrado() { return encerrado; }
}
