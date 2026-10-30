package br.com.kleber.psigestor.ui;

import br.com.kleber.psigestor.service.AutenticacaoService;
import br.com.kleber.psigestor.service.Contexto;
import br.com.kleber.psigestor.service.EnvioAgendaService;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

/** Preferencias do usuario logado (hoje: tempo do logout por inatividade). */
public class PreferenciasDialog {
    private final Contexto ctx;

    public PreferenciasDialog(Contexto ctx) { this.ctx = ctx; }

    static final String AVISO_PERIODO = "Novo: o marcador {periodo} mostra o período consultado (ex.: 01/10 a 31/10). "
            + "Seu modelo personalizado foi mantido; acrescente {periodo} se quiser.";

    /** Modelo personalizado (o padrao e null) que ainda nao usa {periodo}. */
    static boolean precisaAvisarPeriodo(String modeloGravado) {
        return modeloGravado != null && !modeloGravado.contains("{periodo}");
    }

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

        String atual = ctx.usuarioLogado().getModeloMensagemAgenda();
        TextArea modelo = new TextArea(atual == null ? EnvioAgendaService.MODELO_PADRAO : atual);
        modelo.setWrapText(true);
        modelo.setPrefRowCount(5);
        modelo.setPrefColumnCount(40);
        Button padrao = new Button("Restaurar padrão");
        padrao.setOnAction(e -> modelo.setText(EnvioAgendaService.MODELO_PADRAO));
        Label legenda = Ui.aviso("Mensagem de horários (Consultar agenda). Use {paciente}, {profissional}, {periodo} "
                + "(ex.: 01/10 a 31/10) e {lista}; {lista} é obrigatório e vira uma linha por atendimento, ex.: "
                + "\"• qua, 05/11 às 14:00 (online)\". Com mais de um status marcado, cada linha mostra o status.");
        legenda.setMaxWidth(420);
        VBox avisos = new VBox(6, legenda);
        if (precisaAvisarPeriodo(atual)) {
            Label novo = Ui.aviso(AVISO_PERIODO);
            novo.setMaxWidth(420);
            avisos.getChildren().add(0, novo);
        }

        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Preferências");
        d.setHeaderText(null);
        d.getDialogPane().setContent(new VBox(8, g, aviso, new Separator(),
                new Label("Mensagem de horários ao paciente"), modelo, Ui.linha(padrao), avisos));
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        // valida antes de fechar: se der erro, a janela continua aberta
        d.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            if (!Ui.executar(() -> {
                ctx.autenticacao.alterarModeloMensagem(modelo.getText());   // valida {lista} antes de gravar algo
                ctx.autenticacao.alterarInatividade(inatividade.getValue());
            })) e.consume();
        });
        d.showAndWait();
    }
}
