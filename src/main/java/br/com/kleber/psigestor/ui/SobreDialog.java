package br.com.kleber.psigestor.ui;

import br.com.kleber.psigestor.util.LogTecnico;
import br.com.kleber.psigestor.util.Versao;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

/** Versao, licenca, repositorio e pasta de dados (para suporte e para conferir a atualizacao). */
final class SobreDialog {

    private SobreDialog() { }

    static void mostrar() {
        Label nome = new Label("PsiGestor");
        nome.getStyleClass().add("login-marca");
        Hyperlink repositorio = new Hyperlink(Versao.REPOSITORIO);
        repositorio.setOnAction(e -> abrir(Versao.REPOSITORIO));
        Button termos = new Button("Ver termos de uso");
        termos.setOnAction(e -> Ui.executar(TermosDialog::ver));
        VBox corpo = new VBox(8, nome,
                new Label("Versao " + Versao.atual()),
                new Label("Gestao de consultorio de psicologia com sigilo por padrao."),
                new Label("Software livre - Licenca MIT. Fornecido \"como esta\", sem garantia."),
                repositorio,
                new Label("Seus dados ficam em: " + Path.of(System.getProperty("user.home"), "PsiGestor")),
                termos);
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Sobre o PsiGestor");
        d.setHeaderText(null);
        d.getDialogPane().setContent(corpo);
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        d.showAndWait();
    }

    private static void abrir(String url) {
        try {
            Desktop.getDesktop().browse(URI.create(url));
        } catch (IOException | UnsupportedOperationException e) {
            LogTecnico.erro("Nao foi possivel abrir o navegador", e);
            Ui.info("Acesse no navegador:\n" + url);
        }
    }
}
