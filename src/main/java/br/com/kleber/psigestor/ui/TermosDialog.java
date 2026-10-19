package br.com.kleber.psigestor.ui;

import br.com.kleber.psigestor.util.Termos;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;

/** Termos de uso: aceite obrigatorio no primeiro acesso de cada usuario e a cada versao nova. */
final class TermosDialog {

    private TermosDialog() { }

    /** true so se o usuario marcou "Li e aceito" e clicou Aceitar. Fechar a janela = recusar. */
    static boolean pedirAceite() {
        ButtonType aceitar = new ButtonType("Aceitar", ButtonBar.ButtonData.OK_DONE);
        ButtonType recusar = new ButtonType("Recusar", ButtonBar.ButtonData.CANCEL_CLOSE);
        CheckBox li = new CheckBox("Li e aceito os termos de uso");
        Dialog<ButtonType> d = dialogo(li);
        d.getDialogPane().getButtonTypes().addAll(aceitar, recusar);
        d.getDialogPane().lookupButton(aceitar).disableProperty().bind(li.selectedProperty().not());
        return d.showAndWait().filter(b -> b == aceitar).isPresent() && li.isSelected();
    }

    /** Somente leitura (tela Sobre). */
    static void ver() {
        Dialog<ButtonType> d = dialogo(null);
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        d.showAndWait();
    }

    private static Dialog<ButtonType> dialogo(CheckBox aceite) {
        TextArea texto = new TextArea(Termos.texto());
        texto.setEditable(false);
        texto.setWrapText(true);
        texto.setPrefSize(640, 420);
        VBox corpo = aceite == null ? new VBox(10, texto) : new VBox(10, texto, aceite);
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Termos de uso do PsiGestor");
        d.setHeaderText(null);
        d.getDialogPane().setContent(corpo);
        return d;
    }
}
