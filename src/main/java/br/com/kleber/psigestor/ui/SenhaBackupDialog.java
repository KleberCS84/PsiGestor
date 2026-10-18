package br.com.kleber.psigestor.ui;

import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.util.Optional;

/** Pede a senha do backup na hora; ela nao fica guardada em lugar nenhum. */
final class SenhaBackupDialog {

    /** confirmacao == null quando a senha foi pedida uma vez so (restauracao). */
    record Senhas(char[] senha, char[] confirmacao) { }

    private SenhaBackupDialog() { }

    static Optional<Senhas> pedir(String titulo, String aviso, boolean repetir) {
        PasswordField senha = new PasswordField();
        PasswordField confirmacao = new PasswordField();
        GridPane g = Ui.formulario();
        g.addRow(0, new Label("Senha do backup"), senha);
        if (repetir) g.addRow(1, new Label("Repita a senha"), confirmacao);
        Label l = Ui.aviso(aviso);
        l.setMaxWidth(420);

        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle(titulo);
        d.setHeaderText(null);
        d.getDialogPane().setContent(new VBox(8, g, l));
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Optional<Senhas> r = d.showAndWait().filter(b -> b == ButtonType.OK)
                .map(b -> new Senhas(senha.getText().toCharArray(),
                        repetir ? confirmacao.getText().toCharArray() : null));
        senha.clear();
        confirmacao.clear();
        return r;
    }
}
