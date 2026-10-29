package br.com.kleber.psigestor.ui;

import br.com.kleber.psigestor.service.AutenticacaoService;
import br.com.kleber.psigestor.service.Contexto;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

/** Preferencias do usuario logado (hoje: tempo do logout por inatividade). */
public class PreferenciasDialog {
    private final Contexto ctx;

    public PreferenciasDialog(Contexto ctx) { this.ctx = ctx; }

    static String rotulo(int minutos) {
        return minutos + " minutos" + (minutos == Contexto.PADRAO_INATIVIDADE_MIN ? " (padrão)" : "");
    }

    public void mostrar() {
        ComboBox<Integer> inatividade = new ComboBox<>();
        inatividade.getItems().setAll(AutenticacaoService.OPCOES_INATIVIDADE);
        Ui.rotular(inatividade, PreferenciasDialog::rotulo);
        inatividade.setValue(ctx.usuarioLogado().getMinutosInatividade());

        GridPane g = Ui.formulario();
        g.addRow(0, new Label("Encerrar a sessão após"), inatividade);
        Label aviso = Ui.aviso("Sem atividade nesse tempo, o PsiGestor volta à tela de login, protegendo agenda e "
                + "prontuário de quem passar pelo computador (sigilo, Código de Ética art. 9º). "
                + "Um aviso aparece 1 minuto antes.");
        aviso.setMaxWidth(420);

        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Preferências");
        d.setHeaderText(null);
        d.getDialogPane().setContent(new VBox(8, g, aviso));
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        // valida antes de fechar: se der erro, a janela continua aberta
        d.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            if (!Ui.executar(() -> ctx.autenticacao.alterarInatividade(inatividade.getValue()))) e.consume();
        });
        d.showAndWait();
    }
}
