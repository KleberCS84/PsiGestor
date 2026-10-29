package br.com.kleber.psigestor.ui;

import br.com.kleber.psigestor.service.BackupService;
import br.com.kleber.psigestor.service.Contexto;
import br.com.kleber.psigestor.util.Database;
import br.com.kleber.psigestor.util.LogTecnico;
import br.com.kleber.psigestor.util.Versao;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;

public class PsiGestorApp extends Application {

    private Contexto ctx;
    private Stage palco;
    private MonitorInatividade inatividade;

    @Override
    public void start(Stage stage) throws Exception {
        java.util.Locale.setDefault(java.util.Locale.of("pt", "BR")); // datas dd/MM/yyyy nos DatePickers
        // Banco fica na pasta do usuario: C:\Users\<voce>\PsiGestor\psigestor.db
        Path pasta = Path.of(System.getProperty("user.home"), "PsiGestor");
        Files.createDirectories(pasta);
        LogTecnico.iniciar(pasta.resolve("logs"));
        // erro fora de Ui.executar (temporizador, listener): vai para o log e o usuario ve aviso generico
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            LogTecnico.erro("Excecao nao tratada na thread " + thread.getName(), e);
            // runLater: Alert.showAndWait e proibido durante animacao/layout
            if (Platform.isFxApplicationThread()) Platform.runLater(() -> Ui.erro(Ui.ERRO_GENERICO));
        });
        // backup/restauracao interrompidos (queda de energia) deixam copias do banco em claro
        BackupService.limparTemporarios(pasta);
        try {
            ctx = new Contexto("jdbc:sqlite:" + pasta.resolve("psigestor.db"));
        } catch (RuntimeException e) {
            // ex.: migracao de esquema que falhou; sem isto a janela simplesmente nao abriria
            LogTecnico.erro("Falha ao abrir o banco na partida", e);
            Ui.erro(mensagemFalhaAoAbrir(pasta));
            Platform.exit();
            return;
        }
        palco = stage;
        stage.setTitle("PsiGestor " + Versao.atual() + " - consultório de psicologia");
        stage.setMinWidth(1000);
        stage.setMinHeight(650);
        mostrarLogin();
        stage.show();
    }

    /** Sem detalhe tecnico (pode ter caminho/SQL): ele fica no log da pasta logs. */
    static String mensagemFalhaAoAbrir(Path pasta) {
        return "Não foi possível abrir ou atualizar o banco de dados do PsiGestor. Nenhum dado foi apagado.\n\n"
                + "Se esta versão do programa precisou atualizar o banco, a cópia de antes da atualização está em\n"
                + pasta.resolve("psigestor.db.pre-v" + Database.VERSAO_ESQUEMA) + ".\n"
                + "Não abra o banco com uma versão mais antiga do PsiGestor.\n\n"
                + "Detalhe técnico para o suporte: " + pasta.resolve("logs") + "\n"
                + "O programa será fechado.";
    }

    void mostrarLogin() {
        trocar(new TelaLogin(ctx, this::mostrarPrincipal).criar());
    }

    void mostrarPrincipal() {
        Ui.sessaoIniciada();
        TelaPrincipal principal = new TelaPrincipal(ctx, () -> {
            pararInatividade();
            Ui.executar(ctx::sair);
            mostrarLogin();
        });
        trocar(principal.criar());
        // qualquer tecla/clique em qualquer janela do app conta como atividade (B-12)
        inatividade = new MonitorInatividade(ctx, palco, principal::temTextoNaoSalvo, () -> {
            inatividade = null;
            mostrarLogin();
        });
        inatividade.iniciar();
    }

    private void pararInatividade() {
        if (inatividade != null) inatividade.parar();
        inatividade = null;
    }

    private Scene trocar(Parent raiz) {
        Scene cena = new Scene(raiz, 1180, 720);
        cena.getStylesheets().add(getClass().getResource("/ui/estilo.css").toExternalForm());
        palco.setScene(cena);
        return cena;
    }

    @Override
    public void stop() {
        if (ctx != null) { ctx.sair(); ctx.close(); }
        LogTecnico.encerrar();
    }

    public static void main(String[] args) { launch(args); }
}
