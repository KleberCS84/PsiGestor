package br.com.kleber.psigestor.ui;

import br.com.kleber.psigestor.service.Contexto;
import javafx.application.Application;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;

public class PsiGestorApp extends Application {

    private Contexto ctx;
    private Stage palco;

    @Override
    public void start(Stage stage) throws Exception {
        java.util.Locale.setDefault(java.util.Locale.of("pt", "BR")); // datas dd/MM/yyyy nos DatePickers
        // Banco fica na pasta do usuario: C:\Users\<voce>\PsiGestor\psigestor.db
        Path pasta = Path.of(System.getProperty("user.home"), "PsiGestor");
        Files.createDirectories(pasta);
        ctx = new Contexto("jdbc:sqlite:" + pasta.resolve("psigestor.db"));
        palco = stage;
        stage.setTitle("PsiGestor - consultorio de psicologia");
        stage.setMinWidth(1000);
        stage.setMinHeight(650);
        mostrarLogin();
        stage.show();
    }

    void mostrarLogin() {
        trocar(new TelaLogin(ctx, this::mostrarPrincipal).criar());
    }

    void mostrarPrincipal() {
        trocar(new TelaPrincipal(ctx, () -> { ctx.sair(); mostrarLogin(); }).criar());
    }

    private void trocar(Parent raiz) {
        Scene cena = new Scene(raiz, 1180, 720);
        cena.getStylesheets().add(getClass().getResource("/ui/estilo.css").toExternalForm());
        palco.setScene(cena);
    }

    @Override
    public void stop() {
        if (ctx != null) { ctx.sair(); ctx.close(); }
    }

    public static void main(String[] args) { launch(args); }
}
