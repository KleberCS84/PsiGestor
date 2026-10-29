package br.com.kleber.psigestor.ui;

import br.com.kleber.psigestor.service.AutenticacaoService;
import br.com.kleber.psigestor.service.Contexto;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

/** Janela de troca de senha do usuario logado. */
public class TrocaSenhaDialog {
    private final Contexto ctx;

    public TrocaSenhaDialog(Contexto ctx) { this.ctx = ctx; }

    public void mostrar() {
        PasswordField atual = new PasswordField();
        PasswordField nova = new PasswordField();
        PasswordField repetir = new PasswordField();
        GridPane g = Ui.formulario();
        g.addRow(0, new Label("Senha atual"), atual);
        g.addRow(1, new Label("Nova senha (min. " + AutenticacaoService.SENHA_MINIMA + ")"), nova);
        g.addRow(2, new Label("Repita a nova senha"), repetir);

        VBox conteudo = new VBox(8, g);
        if (ctx.usuarioLogado().isPsicologo()) {
            Label aviso = Ui.aviso("Seus registros de prontuário continuam legíveis com a nova senha. "
                    + "Se a senha for perdida, só o código de recuperação devolve o acesso.");
            aviso.setMaxWidth(420);
            Button novoCodigo = new Button("Gerar novo código de recuperação");
            novoCodigo.setOnAction(e -> {
                if (!Ui.confirmar("Gerar um novo código de recuperação? O código anterior deixa de valer.\n"
                        + "Usa a senha digitada em \"Senha atual\".")) return;
                String[] codigo = {null};
                if (Ui.executar(() -> codigo[0] = ctx.autenticacao.gerarCodigoRecuperacao(atual.getText().toCharArray())))
                    CodigoRecuperacaoDialog.mostrar(codigo[0]);
            });
            conteudo.getChildren().addAll(aviso, novoCodigo);
        }

        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Trocar senha");
        d.setHeaderText(null);
        d.getDialogPane().setContent(conteudo);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        // Valida antes de fechar: em caso de erro a janela continua aberta
        Button ok = (Button) d.getDialogPane().lookupButton(ButtonType.OK);
        ok.setText("Trocar senha");
        ok.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            if (!nova.getText().equals(repetir.getText())) {
                Ui.erro("As novas senhas não conferem.");
                e.consume();
                return;
            }
            if (!Ui.executar(() -> ctx.autenticacao.trocarSenha(
                    atual.getText().toCharArray(), nova.getText().toCharArray()))) {
                e.consume();
            }
        });
        if (d.showAndWait().filter(b -> b == ButtonType.OK).isPresent())
            Ui.info("Senha alterada. Use a nova senha no próximo login.");
        atual.clear();
        nova.clear();
        repetir.clear();
    }
}
