package br.com.kleber.psigestor.ui;

import br.com.kleber.psigestor.model.Atendimento;
import br.com.kleber.psigestor.model.StatusAtendimento;
import br.com.kleber.psigestor.service.Contexto;
import br.com.kleber.psigestor.service.FinanceiroService;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.layout.FlowPane;

import java.time.LocalDate;

public class FinanceiroView {
    private final Contexto ctx;
    private final DatePicker de = new DatePicker(LocalDate.now().withDayOfMonth(1));
    private final DatePicker ate = new DatePicker(LocalDate.now().withDayOfMonth(LocalDate.now().lengthOfMonth()));
    private final FlowPane cards = new FlowPane(14, 14);
    private final TableView<FinanceiroService.LinhaRepasse> repasse = Ui.tabela();
    private final TableView<Atendimento> lancamentos = Ui.tabela();

    public FinanceiroView(Contexto ctx) { this.ctx = ctx; }

    public Node criar() {
        Button atualizar = new Button("Atualizar");
        atualizar.getStyleClass().add("primario");
        atualizar.setOnAction(e -> carregar());

        repasse.getColumns().add(Ui.col("Profissional", FinanceiroService.LinhaRepasse::profissional, 220));
        repasse.getColumns().add(Ui.col("Sessoes", FinanceiroService.LinhaRepasse::sessoes, 80));
        repasse.getColumns().add(Ui.col("Bruto", l -> Ui.moeda(l.bruto()), 120));
        repasse.getColumns().add(Ui.col("Parte da clinica", l -> Ui.moeda(l.parteClinica()), 130));
        repasse.getColumns().add(Ui.col("Liquido profissional", l -> Ui.moeda(l.liquidoProfissional()), 150));
        repasse.setMinHeight(150);
        repasse.setPrefHeight(170);
        lancamentos.setMinHeight(200);

        lancamentos.getColumns().add(Ui.col("Data", a -> a.getInicio().format(Ui.DATA_HORA), 130));
        lancamentos.getColumns().add(Ui.col("Paciente", Atendimento::getPacienteNome, 200));
        lancamentos.getColumns().add(Ui.col("Profissional", Atendimento::getProfissionalNome, 160));
        lancamentos.getColumns().add(Ui.col("Status", a -> a.getStatus() == StatusAtendimento.FALTOU
                ? "Falta cobrada" : "Realizada", 110));
        lancamentos.getColumns().add(Ui.col("Tipo", a -> a.getConvenioId() == null ? "Particular" : "Convenio", 90));
        lancamentos.getColumns().add(Ui.col("Valor", a -> Ui.moeda(a.getValorCentavos()), 100));
        lancamentos.getColumns().add(Ui.col("Pago", a -> a.isPago() ? a.getFormaPagamento() : "PENDENTE", 110));
        lancamentos.getColumns().add(Ui.col("Recibo", a -> a.getConvenioId() != null ? "n/a"
                : a.isReciboEmitido() ? "Emitido" : "A emitir", 90));

        carregar();
        return Ui.tela(Ui.titulo("Financeiro"),
                Ui.linha(new Label("De"), de, new Label("ate"), ate, atualizar),
                cards,
                Ui.titulo("Repasse por profissional"), repasse,
                Ui.titulo("Lancamentos do periodo"), lancamentos,
                Ui.aviso("Recibos de pessoa fisica: emita pelo app/portal Receita Saude (Receita Federal) e marque "
                        + "\"Recibo emitido\" na Agenda. Este sistema nao emite documento fiscal. "
                        + "Os valores aqui sao gerenciais - confira com seu contador."));
    }

    private void carregar() {
        Ui.executar(() -> {
            var r = ctx.financeiro.resumo(de.getValue(), ate.getValue());
            cards.getChildren().setAll(
                    Ui.card("Faturado", Ui.moeda(r.faturado()), "cor-verde"),
                    Ui.card("Recebido", Ui.moeda(r.recebido()), "cor-azul"),
                    Ui.card("A receber", Ui.moeda(r.aReceber()), "cor-laranja"),
                    Ui.card("Sessoes realizadas", String.valueOf(r.realizados()), "cor-roxo"),
                    Ui.card("Faltas (" + String.format("%.0f%%", r.taxaFaltas() * 100) + ")", String.valueOf(r.faltas()), "cor-rosa"),
                    Ui.card("Faltas cobradas (" + r.faltasCobradas() + ")", Ui.moeda(r.faltasCobradasCentavos()), "cor-rosa"),
                    Ui.card("Recibos a emitir", String.valueOf(r.recibosPendentes()), "cor-cinza"));
            repasse.getItems().setAll(ctx.financeiro.repasse(de.getValue(), ate.getValue()));
            lancamentos.getItems().setAll(ctx.agenda.periodo(de.getValue(), ate.getValue(), null).stream()
                    .filter(a -> a.getStatus() == StatusAtendimento.REALIZADO
                            || (a.getStatus() == StatusAtendimento.FALTOU && a.isPago())).toList());
        });
    }
}
