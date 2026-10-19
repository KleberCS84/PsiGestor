package br.com.kleber.psigestor.ui;

import br.com.kleber.psigestor.model.*;
import br.com.kleber.psigestor.service.AgendaService;
import br.com.kleber.psigestor.service.Contexto;
import br.com.kleber.psigestor.util.RegraNegocioException;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
        Ui.executar(() -> filtroProf.getItems().addAll(ctx.cadastros.listarPsicologos()));
        filtroProf.setPromptText("Todos os profissionais");
        dia.setOnAction(e -> carregar());
        filtroProf.setOnAction(e -> carregar());
        Button anterior = new Button("<");
        anterior.setOnAction(e -> dia.setValue(diaSelecionado().minusDays(1)));
        Button proximo = new Button(">");
        proximo.setOnAction(e -> dia.setValue(diaSelecionado().plusDays(1)));
        Button novo = new Button("+ Novo agendamento");
        novo.getStyleClass().add("primario");
        novo.setOnAction(e -> novoAgendamento());

        tabela.getColumns().add(Ui.col("Hora", a -> a.getInicio().format(Ui.HORA) + "-" + a.getFim().format(Ui.HORA), 100));
        tabela.getColumns().add(Ui.col("Paciente", Atendimento::getPacienteNome, 200));
        tabela.getColumns().add(Ui.col("Profissional", Atendimento::getProfissionalNome, 160));
        tabela.getColumns().add(Ui.col("Serviço", Atendimento::getServicoNome, 160));
        tabela.getColumns().add(Ui.col("Modalidade", a -> a.getModalidade().rotulo(), 95));
        tabela.getColumns().add(Ui.col("Status", a -> a.getStatus().rotulo(), 100));
        tabela.getColumns().add(Ui.col("Valor", a -> Ui.moeda(a.getValorCentavos()) + (a.getConvenioId() != null ? " (conv.)" : ""), 110));
        tabela.getColumns().add(Ui.col("Pago", a -> a.isPago() ? "Sim - " + Ui.formaPagamento(a.getFormaPagamento()) : "Não", 100));
        tabela.getColumns().add(Ui.col("Recibo", a -> a.isReciboEmitido() ? "Emitido" : "-", 80));

        var acoes = Ui.linha(
                botaoStatus("Confirmar", StatusAtendimento.CONFIRMADO),
                botaoStatus("Realizado", StatusAtendimento.REALIZADO),
                botaoStatus("Faltou", StatusAtendimento.FALTOU),
                botaoStatus("Cancelar", StatusAtendimento.CANCELADO),
                botao("Registrar pagamento", this::pagamento),
                botao("Recibo emitido", a -> {
                    if (Ui.confirmar("Confirma que o recibo deste atendimento já foi emitido no Receita Saúde?"))
                        Ui.executar(() -> ctx.agenda.marcarReciboEmitido(a.getId()));
                }),
                botao("Abrir prontuário", a -> principal.abrirProntuario(a.getPacienteId())));
        carregar();
        return Ui.tela(Ui.titulo("Agenda"),
                Ui.linha(anterior, dia, proximo, filtroProf, Ui.espaco(), novo),
                tabela, acoes);
    }

    private void carregar() {
        Usuario p = filtroProf.getValue();
        Ui.executar(() -> tabela.getItems().setAll(ctx.agenda.doDia(diaSelecionado(), p == null ? null : p.getId())));
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
        // a tela lista os rotulos com acento; o service recebe o valor gravado
        var rotulos = AgendaService.FORMAS_PAGAMENTO.stream().map(Ui::formaPagamento).toList();
        ChoiceDialog<String> d = new ChoiceDialog<>(rotulos.get(0), rotulos);
        d.setHeaderText("Pagamento de " + a.getPacienteNome() + " - " + Ui.moeda(a.getValorCentavos()));
        d.setTitle("Registrar pagamento");
        d.showAndWait().ifPresent(rotulo -> Ui.executar(() -> ctx.agenda.registrarPagamento(a.getId(),
                AgendaService.FORMAS_PAGAMENTO.get(rotulos.indexOf(rotulo)))));
    }

    private void novoAgendamento() {
        ComboBox<Paciente> paciente = new ComboBox<>();
        ComboBox<Usuario> prof = new ComboBox<>();
        ComboBox<Servico> servico = new ComboBox<>();
        if (!Ui.executar(() -> {
            paciente.getItems().setAll(ctx.pacienteService.pesquisar("", false));
            prof.getItems().setAll(ctx.cadastros.listarPsicologos());
            servico.getItems().setAll(ctx.cadastros.listarServicos());
        })) return;
        if (ctx.getSessao().usuario().isPsicologo()) prof.setValue(ctx.getSessao().usuario());
        if (!servico.getItems().isEmpty()) servico.setValue(servico.getItems().get(0));
        DatePicker data = new DatePicker(dia.getValue());
        TextField hora = new TextField("08:00");
        ComboBox<Modalidade> modalidade = new ComboBox<>();
        modalidade.getItems().setAll(Modalidade.values());
        modalidade.setValue(Modalidade.PRESENCIAL);
        Ui.rotular(modalidade, Modalidade::rotulo);
        CheckBox convenio = new CheckBox("Cobrar pelo convênio do paciente");

        GridPane g = Ui.formulario();
        g.addRow(0, new Label("Paciente"), paciente);
        g.addRow(1, new Label("Profissional"), prof);
        g.addRow(2, new Label("Serviço"), servico);
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
                    throw new br.com.kleber.psigestor.util.RegraNegocioException("Preencha paciente, profissional e serviço.");
                ctx.agenda.agendar(paciente.getValue().getId(), prof.getValue().getId(), servico.getValue().getId(),
                        montarInicio(data.getValue(), hora.getText()), modalidade.getValue(), convenio.isSelected());
            });
            if (!ok) ev.consume();
        });
        d.showAndWait();
        if (data.getValue() != null) dia.setValue(data.getValue());
        carregar();
    }

    /** Data do DatePicker (pode vir nula se o usuario limpar o campo) + hora digitada "HH:mm". */
    static LocalDateTime montarInicio(LocalDate data, String hora) {
        if (data == null) throw new RegraNegocioException("Informe a data.");
        try {
            if (hora == null) throw new DateTimeParseException("vazio", "", 0);
            return data.atTime(LocalTime.parse(hora.trim()));
        } catch (DateTimeParseException ex) {
            throw new RegraNegocioException("Hora inválida. Use HH:mm, ex.: 14:30");
        }
    }

    /** O usuario pode apagar o texto do DatePicker; nesse caso volta para hoje. */
    private LocalDate diaSelecionado() {
        if (dia.getValue() == null) dia.setValue(LocalDate.now());
        return dia.getValue();
    }
}
