package br.com.kleber.psigestor.ui;

import br.com.kleber.psigestor.service.AgendaService;
import br.com.kleber.psigestor.service.Contexto;
import br.com.kleber.psigestor.util.RegraNegocioException;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Previa do agendamento em lote: todas as datas, com as que conflitam marcadas. A psicologa pode
 * "pular conflitos" (tira as linhas com problema) ou ajustar data/hora de cada linha ali mesmo; a situacao
 * e recalculada pelo service. "Salvar série" grava tudo em uma transacao (tudo ou nada).
 */
class PreviaLoteDialog {
    private static final String ESTILO_PROBLEMA = "-fx-text-fill: #b3261e; -fx-font-weight: bold;";

    private static final class Linha {
        final Label numero = new Label();
        final DatePicker data = new DatePicker();
        final TextField hora = new TextField();
        final Label situacao = new Label();
        boolean problema = true;
    }

    private final Contexto ctx;
    private final AgendaService.ParametrosLote params;
    private final List<Linha> linhas = new ArrayList<>();
    private final GridPane grade = new GridPane();
    private final Button pular = new Button("Pular conflitos");
    private final Label resumo = new Label();
    private Node botaoSalvar;
    private boolean salvou;

    PreviaLoteDialog(Contexto ctx, AgendaService.ParametrosLote params) {
        this.ctx = ctx;
        this.params = params;
        grade.setHgap(8);
        grade.setVgap(6);
        grade.setPadding(new Insets(4));
    }

    /** true se a serie foi gravada. */
    boolean mostrar(List<LocalDateTime> datas) {
        for (LocalDateTime d : datas) linhas.add(novaLinha(d));
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Prévia da série");
        ScrollPane rolagem = new ScrollPane(grade);
        rolagem.setFitToWidth(true);
        rolagem.setPrefViewportHeight(340);
        rolagem.setPrefViewportWidth(620);
        Label orientacao = new Label("Confira as datas. Linhas em vermelho têm conflito de horário: use "
                + "\"Pular conflitos\" ou ajuste a data e a hora da própria linha. Nada é salvo até você confirmar.");
        orientacao.setWrapText(true);
        orientacao.setMaxWidth(620);
        VBox conteudo = new VBox(8, orientacao, rolagem, Ui.linha(pular), resumo);
        conteudo.setPadding(new Insets(8));
        d.getDialogPane().setContent(conteudo);
        ButtonType salvar = new ButtonType("Salvar série", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(salvar, ButtonType.CANCEL);
        botaoSalvar = d.getDialogPane().lookupButton(salvar);
        botaoSalvar.addEventFilter(ActionEvent.ACTION, ev -> {
            // erro (conflito de ultima hora, etc.): tudo desfeito, a previa continua aberta
            if (Ui.executar(() -> ctx.agenda.agendarLote(params, datasOrdenadas()))) salvou = true;
            else ev.consume();
        });
        pular.setOnAction(e -> {
            linhas.removeIf(l -> l.problema);
            reconstruir();
            recalcular();
        });
        reconstruir();
        recalcular();
        d.showAndWait();
        return salvou;
    }

    private Linha novaLinha(LocalDateTime inicio) {
        Linha l = new Linha();
        l.data.setValue(inicio.toLocalDate());
        l.hora.setText(inicio.format(Ui.HORA));
        l.hora.setPrefColumnCount(5);
        l.data.valueProperty().addListener((o, a, b) -> recalcular());
        l.hora.setOnAction(e -> recalcular());
        l.hora.focusedProperty().addListener((o, a, focado) -> { if (!focado) recalcular(); });
        return l;
    }

    private void reconstruir() {
        grade.getChildren().clear();
        grade.add(new Label("Nº"), 0, 0);
        grade.add(new Label("Data"), 1, 0);
        grade.add(new Label("Hora"), 2, 0);
        grade.add(new Label("Situação"), 3, 0);
        for (int i = 0; i < linhas.size(); i++) {
            Linha l = linhas.get(i);
            l.numero.setText(String.valueOf(i + 1));
            grade.add(l.numero, 0, i + 1);
            grade.add(l.data, 1, i + 1);
            grade.add(l.hora, 2, i + 1);
            grade.add(l.situacao, 3, i + 1);
        }
    }

    /** Lê as linhas, pede a previa ao service e atualiza situacao e botoes. */
    private void recalcular() {
        if (botaoSalvar == null) return;   // montagem inicial: os listeners ainda nao tem botao
        if (linhas.isEmpty()) {
            resumo.setText("Nenhuma sessão restante.");
            pular.setDisable(true);
            botaoSalvar.setDisable(true);
            return;
        }
        List<LocalDateTime> datas = new ArrayList<>();
        boolean legivel = true;
        for (Linha l : linhas) {
            try {
                datas.add(AgendaView.montarInicio(l.data.getValue(), l.hora.getText()));
            } catch (RegraNegocioException e) {
                legivel = false;
                marcar(l, e.getMessage(), true);
            }
        }
        if (!legivel) {
            resumo.setText("Corrija as linhas com data ou hora inválida.");
            pular.setDisable(false);
            botaoSalvar.setDisable(true);
            return;
        }
        List<AgendaService.ItemPrevia> itens = new ArrayList<>();
        if (!Ui.executar(() -> itens.addAll(ctx.agenda.previa(params, datas)))) {
            botaoSalvar.setDisable(true);
            return;
        }
        int problemas = 0;
        for (int i = 0; i < itens.size(); i++) {
            AgendaService.ItemPrevia it = itens.get(i);
            Linha l = linhas.get(i);
            switch (it.situacao()) {
                case OK -> marcar(l, "Livre", false);
                case CONFLITO -> marcar(l, "Conflito: " + it.motivo(), true);
                case INVALIDA -> marcar(l, "Fora do prazo: " + it.motivo(), true);
            }
            if (!it.ok()) problemas++;
        }
        resumo.setText(linhas.size() + " sessões na série" + (problemas == 0 ? ": nenhum conflito."
                : "; " + problemas + " com problema (pule ou ajuste para salvar)."));
        pular.setDisable(problemas == 0);
        botaoSalvar.setDisable(problemas > 0);
    }

    private static void marcar(Linha l, String texto, boolean problema) {
        l.problema = problema;
        l.situacao.setText(texto);
        l.situacao.setStyle(problema ? ESTILO_PROBLEMA : "");
    }

    /** Em ordem cronologica: a numeracao "sessão n de m" segue a ordem das datas, mesmo apos ajustes. */
    private List<LocalDateTime> datasOrdenadas() {
        List<LocalDateTime> datas = new ArrayList<>();
        for (Linha l : linhas) datas.add(AgendaView.montarInicio(l.data.getValue(), l.hora.getText()));
        Collections.sort(datas);
        return datas;
    }
}
