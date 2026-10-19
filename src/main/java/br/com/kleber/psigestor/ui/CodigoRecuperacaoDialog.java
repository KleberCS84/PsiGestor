package br.com.kleber.psigestor.ui;

import br.com.kleber.psigestor.util.CodigoRecuperacao;
import javafx.print.PrinterJob;
import javafx.scene.control.*;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Mostra o codigo de recuperacao UMA vez. So fecha depois que o usuario digita os 4 ultimos caracteres,
 * para garantir que foi anotado. O codigo nao fica guardado em lugar nenhum depois disso.
 */
public class CodigoRecuperacaoDialog {

    private CodigoRecuperacaoDialog() { }

    public static void mostrar(String codigo) {
        Label rotulo = new Label(codigo);
        rotulo.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 18px; -fx-font-weight: bold;");
        VBox bloco = new VBox(4, new Label("PsiGestor - código de recuperação"), rotulo);

        Button copiar = new Button("Copiar");
        copiar.setOnAction(e -> {
            ClipboardContent c = new ClipboardContent();
            c.putString(codigo);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(c);
        });
        Button imprimir = new Button("Imprimir");
        imprimir.setOnAction(e -> {
            PrinterJob job = PrinterJob.createPrinterJob();
            if (job == null) { Ui.erro("Nenhuma impressora disponível."); return; }
            if (job.showPrintDialog(imprimir.getScene().getWindow()) && job.printPage(bloco)) job.endJob();
            else job.cancelJob();
        });

        TextField confirmar = new TextField();
        confirmar.setPromptText("4 últimos caracteres");
        Label aviso = Ui.aviso("Guarde este código FORA do computador (papel em local seguro, cofre de senhas). "
                + "Ele é a única forma de recuperar o acesso ao prontuário se a senha for esquecida, e não será "
                + "mostrado de novo. Qualquer código anterior deixou de valer.");
        aviso.setMaxWidth(460);
        VBox conteudo = new VBox(10, bloco, new HBox(8, copiar, imprimir), aviso,
                new Label("Para confirmar que anotou, digite os 4 últimos caracteres:"), confirmar);

        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Código de recuperação");
        d.setHeaderText(null);
        d.getDialogPane().setContent(conteudo);
        d.getDialogPane().getButtonTypes().add(ButtonType.OK);
        String esperado = CodigoRecuperacao.ultimos4(codigo);
        Button ok = (Button) d.getDialogPane().lookupButton(ButtonType.OK);
        ok.setText("Anotei o código");
        ok.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            if (!confirmar.getText().trim().equalsIgnoreCase(esperado)) {
                Ui.erro("Os 4 últimos caracteres não conferem. Confira o código anotado.");
                e.consume();
            }
        });
        // fechar pela janela (X/Esc) nao dispensa a confirmacao
        d.setOnCloseRequest(e -> {
            if (!confirmar.getText().trim().equalsIgnoreCase(esperado)) e.consume();
        });
        d.showAndWait();
        confirmar.clear();
    }
}
