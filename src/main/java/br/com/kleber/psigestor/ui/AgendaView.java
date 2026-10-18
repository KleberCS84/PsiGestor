package br.com.kleber.psigestor.ui;

import br.com.kleber.psigestor.model.*;
import br.com.kleber.psigestor.service.Contexto;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;

public class AgendaView {
    private final Contexto ctx;
    private final TelaPrincipal principal;
    private final TableView<Atendimento> tabela = Ui.tabela();
    private final DatePicker dia = new DatePicker(LocalDate.now());
    private final ComboBox<Usuario> filtroProf = new ComboBox<>();

    public AgendaView(Contexto ctx, TelaPrincipal principal) {
        this.ctx = ctx;
        this.principal = principal;
    }

    public Node criar() {
        filtroProf.getItems().add(null);
        filtroProf.getItems().addAll(ctx.usuarios.listarPsicologos());
        filtroProf.setPromptText("Todos os profissionais");
        dia.setOnAction(e -> carregar());
        filtroProf.setOnAction(e -> carregar());
        Button anterior = new Button("<");
        anterior.setOnAction(e -> dia.setValue(dia.getValue().minusDays(1)));
        Button proximo = new Button(">");
        proximo.setOnAction(e -> dia.setValue(dia.getValue().plusDays(1)));
        Button novo = new Button("+ Novo agendamento");
        novo.getStyleClass().add("primario");
        novo.setOnAction(e -> novoAgendamento());

        tabela.getColumns().add(Ui.col("Hora", a -> a.getInicio().format(Ui.HORA) + "-" + a.getFim().format(Ui.HORA), 100));
        tabela.getColumns().add(Ui.col("Paciente", Atendimento::getPacienteNome, 200));
        tabela.getColumns().add(Ui.col("Profissional", Atendimento::getProfissionalNome, 160));
        tabela.getColumns().add(Ui.col("Servico", Atendimento::getServicoNome, 160));
        tabela.getColumns().add(Ui.col("Modalidade", Atendimento::getModalidade, 95));
        tabela.getColumns().add(Ui.col("Status", Atendimento::getStatus, 100));
        tabela.getColumns().add(Ui.col("Valor", a -> Ui.moeda(a.getValor()) + (a.getConvenioId() != null ? " (conv.)" : ""), 110));
        tabela.getColumns().add(Ui.col("Pago", a -> a.isPago() ? "Sim - " + a.getFormaPagamento() : "Nao", 100));
        tabela.getColumns().add(Ui.col("Recibo", a -> a.isReciboEmitido() ? "Emitido" : "-", 80));

        var acoes = Ui.linha(
                botaoStatus("Confirmar", StatusAtendimento.CONFIRMADO),
                botaoStatus("Realizado", StatusAtendimento.REALIZADO),
                botaoStatus("Faltou", StatusAtendimento.FALTOU),
                botaoStatus("Cancelar", StatusAtendimento.CANCELADO),
                botao("Registrar pagamento", this::pagamento),
                botao("Recibo emitido", a -> {
                    if (Ui.confirmar("Confirma que o recibo deste atendimento ja foi emitido no Receita Saude?"))
                        Ui.executar(() -> ctx.agenda.marcarReciboEmitido(a.getId()));
                }),
                botao("Abrir prontuario", a -> principal.abrirProntuario(a.getPacienteId())));
        carregar();
        return Ui.tela(Ui.titulo("Agenda"),
                Ui.linha(anterior, dia, proximo, filtroProf, Ui.espaco(), novo),
                tabela, acoes);
    }

    private void carregar() {
        Usuario p = filtroProf.getValue();
        tabela.getItems().setAll(ctx.agenda.doDia(dia.getValue(), p == null ? null : p.getId()));
    }

    private Button botaoStatus(String texto, StatusAtendimento status) {
        return botao(texto, a -> Ui.executar(() -> ctx.agenda.alterarStatus(a.getId(), status)));
    }

    private Button botao(String texto, java.util.function.Consumer<Atendimento> acao) {
        Button b = new Button(texto);
        b.setOnAction(e -> Ui.selecionado(tabela).ifPresent(a -> { acao.accept(a); carregar(); }));
        return b;
    }

    private void pagamento(Atendimento a) {
        ChoiceDialog<String> d = new ChoiceDialog<>("PIX", List.of("PIX", "Dinheiro", "Cartao de debito",
                "Cartao de credito", "Transferencia", "Convenio"));
        d.setHeaderText("Pagamento de " + a.getPacienteNome() + " - " + Ui.moeda(a.getValor()));
        d.setTitle("Registrar pagamento");
        d.showAndWait().ifPresent(forma -> Ui.executar(() -> ctx.agenda.registrarPagamento(a.getId(), forma)));
    }

    private void novoAgendamento() {
        ComboBox<Paciente> paciente = new ComboBox<>();
        paciente.getItems().setAll(ctx.pacienteService.pesquisar("", false));
        ComboBox<Usuario> prof = new ComboBox<>();
        prof.getItems().setAll(ctx.usuarios.listarPsicologos());
        if (ctx.getSessao().usuario().isPsicologo()) prof.setValue(ctx.getSessao().usuario());
        ComboBox<Servico> servico = new ComboBox<>();
        servico.getItems().setAll(ctx.servicos.listar());
        if (!servico.getItems().isEmpty()) servico.setValue(servico.getItems().get(0));
        DatePicker data = new DatePicker(dia.getValue());
        TextField hora = new TextField("08:00");
        ComboBox<Modalidade> modalidade = new ComboBox<>();
        modalidade.getItems().setAll(Modalidade.values());
        modalidade.setValue(Modalidade.PRESENCIAL);
        CheckBox convenio = new CheckBox("Cobrar pelo convenio do paciente");

        GridPane g = Ui.formulario();
        g.addRow(0, new Label("Paciente"), paciente);
        g.addRow(1, new Label("Profissional"), prof);
        g.addRow(2, new Label("Servico"), servico);
        g.addRow(3, new Label("Data"), data);
        g.addRow(4, new Label("Hora (HH:mm)"), hora);
        g.addRow(5, new Label("Modalidade"), modalidade);
        g.add(convenio, 1, 6);

        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Novo agendamento");
        d.getDialogPane().setContent(g);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        // valida antes de fechar: se der erro, a janela continua aberta
        d.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            boolean ok = Ui.executar(() -> {
                if (paciente.getValue() == null || prof.getValue() == null || servico.getValue() == null)
                    throw new br.com.kleber.psigestor.util.RegraNegocioException("Preencha paciente, profissional e servico.");
                LocalTime h;
                try { h = LocalTime.parse(hora.getText().trim()); }
                catch (DateTimeParseException ex) {
                    throw new br.com.kleber.psigestor.util.RegraNegocioException("Hora invalida. Use HH:mm, ex.: 14:30");
                }
                ctx.agenda.agendar(paciente.getValue().getId(), prof.getValue().getId(), servico.getValue().getId(),
                        data.getValue().atTime(h), modalidade.getValue(), convenio.isSelected());
            });
            if (!ok) ev.consume();
        });
        d.showAndWait();
        dia.setValue(data.getValue());
        carregar();
    }
}
