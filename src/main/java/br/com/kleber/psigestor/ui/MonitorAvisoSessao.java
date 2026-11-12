package br.com.kleber.psigestor.ui;

import br.com.kleber.psigestor.service.Contexto;
import br.com.kleber.psigestor.service.LembreteSessaoService.AvisoSessao;
import br.com.kleber.psigestor.util.LogTecnico;
import br.com.kleber.psigestor.util.SessaoEncerradaException;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Modality;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Aviso a psicologa X min antes de cada sessao dela. Com a sessao logada, uma checagem por minuto
 * (e uma ao iniciar); o service decide o que e devido e nao repete. Para no logout e no bloqueio por
 * inatividade (PsiGestorApp chama parar()). Nao conta como atividade: o aviso nao mantem a sessao viva.
 */
public class MonitorAvisoSessao {
    private final Contexto ctx;
    private final Window principal;
    private final Runnable aoVerificar;
    private final List<Alert> abertos = new ArrayList<>();
    private Timeline relogio;

    /** aoVerificar: a cada checagem (ex.: atualizar o contador de lembretes pendentes); pode ser null. */
    public MonitorAvisoSessao(Contexto ctx, Window principal, Runnable aoVerificar) {
        this.ctx = ctx;
        this.principal = principal;
        this.aoVerificar = aoVerificar;
    }

    public void iniciar() {
        relogio = new Timeline(new KeyFrame(javafx.util.Duration.minutes(1), e -> verificar()));
        relogio.setCycleCount(Animation.INDEFINITE);
        relogio.play();
        verificar();
    }

    public void parar() {
        if (relogio != null) relogio.stop();
        relogio = null;
        new ArrayList<>(abertos).forEach(Alert::close);
        abertos.clear();
    }

    /** Pacote: o teste chama direto. */
    void verificar() {
        if (relogio == null || ctx.getSessao() == null) return;
        try {
            List<AvisoSessao> avisos = ctx.lembretes.coletarAvisos();
            if (!avisos.isEmpty()) mostrar(avisos);
            if (aoVerificar != null) aoVerificar.run();
        } catch (SessaoEncerradaException e) {
            // a inatividade vence: quem leva ao login e o MonitorInatividade
        } catch (RuntimeException e) {
            // a cada minuto, um dialogo de erro seria pior que o problema: so o log tecnico
            LogTecnico.erro("MonitorAvisoSessao.verificar", e);
        }
    }

    static String texto(List<AvisoSessao> avisos) {
        return avisos.stream().map(AvisoSessao::texto).collect(Collectors.joining("\n"));
    }

    private void mostrar(List<AvisoSessao> avisos) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, texto(avisos), ButtonType.OK);
        a.setTitle("PsiGestor");
        a.setHeaderText("Sessão chegando");
        a.initModality(Modality.NONE);   // nao bloqueia quem esta atendendo ou digitando
        a.initOwner(principal);
        a.setOnHidden(e -> abertos.remove(a));
        abertos.add(a);
        a.show();
    }
}
