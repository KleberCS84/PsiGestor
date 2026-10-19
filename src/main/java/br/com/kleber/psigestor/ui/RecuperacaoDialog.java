package br.com.kleber.psigestor.ui;

import br.com.kleber.psigestor.service.AutenticacaoService;
import br.com.kleber.psigestor.service.Contexto;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

/** "Esqueci a senha": psicologo usa o codigo de recuperacao para definir uma senha nova. */
public class RecuperacaoDialog {
    private final Contexto ctx;

    public RecuperacaoDialog(Contexto ctx) { this.ctx = ctx; }

    public void mostrar() {
        TextField login = new TextField();
        TextField codigo = new TextField();
        codigo.setPromptText("XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX");
        PasswordField nova = new PasswordField();
        PasswordField repetir = new PasswordField();
        GridPane g = Ui.formulario();
        g.addRow(0, new Label("Login"), login);
        g.addRow(1, new Label("Código de recuperação"), codigo);
        g.addRow(2, new Label("Nova senha (min. " + AutenticacaoService.SENHA_MINIMA + ")"), nova);
        g.addRow(3, new Label("Repita a nova senha"), repetir);
        Label aviso = Ui.aviso("Somente psicólogos têm código de recuperação. Secretária: peça a um psicólogo "
                + "para redefinir sua senha na tela Equipe.");
        aviso.setMaxWidth(440);

        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Esqueci a senha");
        d.setHeaderText(null);
        d.getDialogPane().setContent(new VBox(8, g, aviso));
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        String[] novoCodigo = {null};
        Button ok = (Button) d.getDialogPane().lookupButton(ButtonType.OK);
        ok.setText("Redefinir senha");
        ok.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            if (!nova.getText().equals(repetir.getText())) {
                Ui.erro("As novas senhas não conferem.");
                e.consume();
                return;
            }
            if (!Ui.executar(() -> novoCodigo[0] = ctx.autenticacao.recuperarAcesso(
                    login.getText(), codigo.getText(), nova.getText().toCharArray()))) {
                e.consume();
            }
        });
        d.showAndWait();
        codigo.clear();
        nova.clear();
        repetir.clear();
        if (novoCodigo[0] != null) {
            Ui.info("Senha redefinida. O código usado deixou de valer: anote o NOVO código a seguir "
                    + "e depois entre com a nova senha.");
            CodigoRecuperacaoDialog.mostrar(novoCodigo[0]);
        }
    }
}
