package br.com.kleber.psigestor.ui;

import br.com.kleber.psigestor.model.Atendimento;
import br.com.kleber.psigestor.model.StatusAtendimento;
import br.com.kleber.psigestor.service.Contexto;
import br.com.kleber.psigestor.service.FinanceiroService;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

public class PainelView {
    private final Contexto ctx;

    public PainelView(Contexto ctx) { this.ctx = ctx; }

    public Node criar() {
        LocalDate hoje = LocalDate.now();
        VBox tela = Ui.tela(Ui.titulo("Resumo de hoje"), new Label(hoje.format(Ui.DATA)));
        if (!Ui.executar(() -> preencher(tela, hoje)))
            tela.getChildren().add(Ui.aviso("Nao foi possivel carregar o resumo. Abra o Painel novamente."));
        return tela;
    }

    private void preencher(VBox tela, LocalDate hoje) {
        List<Atendimento> doDia = ctx.agenda.doDia(hoje, null);
        long ativos = doDia.stream().filter(a -> a.getStatus() != StatusAtendimento.CANCELADO).count();
        long aguardando = doDia.stream().filter(a -> a.getStatus() == StatusAtendimento.AGENDADO
                || a.getStatus() == StatusAtendimento.CONFIRMADO).count();
        var aniversariantes = ctx.pacienteService.aniversariantes(hoje);

        FlowPane hojeCards = new FlowPane(14, 14,
                Ui.card("Atendimentos hoje", String.valueOf(ativos), "cor-azul"),
                Ui.card("Aguardando atendimento", String.valueOf(aguardando), "cor-laranja"),
                Ui.card("Aniversariantes do dia", String.valueOf(aniversariantes.size()), "cor-rosa"));
        tela.getChildren().add(hojeCards);

        if (!aniversariantes.isEmpty()) {
            tela.getChildren().add(Ui.aviso("Aniversariantes: "
                    + aniversariantes.stream().map(p -> p.getNome()).collect(Collectors.joining(", "))
                    + ". Mensagens so para quem consentiu contato (aba Pacientes > Consentimentos)."));
        }

        TableView<Atendimento> agenda = Ui.tabela();
        agenda.getColumns().add(Ui.col("Hora", a -> a.getInicio().format(Ui.HORA), 70));
        agenda.getColumns().add(Ui.col("Paciente", Atendimento::getPacienteNome, 220));
        agenda.getColumns().add(Ui.col("Profissional", Atendimento::getProfissionalNome, 180));
        agenda.getColumns().add(Ui.col("Modalidade", Atendimento::getModalidade, 100));
        agenda.getColumns().add(Ui.col("Status", Atendimento::getStatus, 110));
        agenda.getItems().setAll(doDia);
        agenda.setPrefHeight(220);
        tela.getChildren().addAll(Ui.titulo("Agenda do dia"), agenda);

        // Gestao (mes corrente)
        LocalDate ini = hoje.withDayOfMonth(1);
        FinanceiroService.Resumo r = ctx.financeiro.resumo(ini, hoje.withDayOfMonth(hoje.lengthOfMonth()));
        FlowPane gestao = new FlowPane(14, 14,
                Ui.card("Pacientes ativos", String.valueOf(ctx.pacienteService.pesquisar("", false).size()), "cor-roxo"),
                Ui.card("Faturado no mes", Ui.moeda(r.faturado()), "cor-verde"),
                Ui.card("A receber", Ui.moeda(r.aReceber()), "cor-laranja"),
                Ui.card("Taxa de faltas", String.format("%.0f%%", r.taxaFaltas() * 100), "cor-rosa"));
        tela.getChildren().addAll(Ui.titulo("Gestao do mes"), gestao);
        if (r.recibosPendentes() > 0) {
            tela.getChildren().add(Ui.aviso(r.recibosPendentes()
                    + " atendimento(s) particular(es) pago(s) sem recibo marcado. Emita no app Receita Saude"
                    + " e depois marque \"Recibo emitido\" na Agenda."));
        }
    }
}
