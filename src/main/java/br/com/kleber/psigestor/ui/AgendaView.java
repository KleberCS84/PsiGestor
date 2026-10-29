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

        // duplo clique na linha = Editar
        tabela.setRowFactory(t -> {
            TableRow<Atendimento> linha = new TableRow<>();
            linha.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !linha.isEmpty()) {
                    editar(linha.getItem());
                    carregar();
                }
            });
            return linha;
        });

        var acoes = Ui.linha(
                botao("Editar", this::editar),
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

    private void novoAgendamento() { formulario(null); }

    /** Botao "Editar" e duplo clique: realizado, falta e cancelado nem abrem o formulario. */
    private void editar(Atendimento a) {
        if (Ui.executar(() -> AgendaService.exigirEditavel(a))) formulario(a);
    }

    /** existente null = novo agendamento; senao, edicao (paciente e profissional nao mudam). */
    private void formulario(Atendimento existente) {
        boolean edicao = existente != null;
        ComboBox<Paciente> paciente = new ComboBox<>();
        ComboBox<Usuario> prof = new ComboBox<>();
        ComboBox<Servico> servico = new ComboBox<>();
        if (!Ui.executar(() -> {
            if (!edicao) {
                paciente.getItems().setAll(ctx.pacienteService.pesquisar("", false));
                prof.getItems().setAll(ctx.cadastros.listarPsicologos());
                if (ctx.usuarioLogado().isPsicologo()) prof.setValue(ctx.usuarioLogado());
            }
            servico.getItems().setAll(ctx.cadastros.listarServicos());
        })) return;
        if (edicao) {
            servico.getItems().stream().filter(s -> s.getId() == existente.getServicoId()).findFirst()
                    .ifPresent(servico::setValue);
        } else if (!servico.getItems().isEmpty()) {
            servico.setValue(servico.getItems().get(0));
        }
        DatePicker data = new DatePicker(edicao ? existente.getInicio().toLocalDate() : dia.getValue());
        TextField hora = new TextField(edicao ? existente.getInicio().format(Ui.HORA) : "08:00");
        int duracaoInicial = edicao ? (int) java.time.Duration.between(existente.getInicio(), existente.getFim()).toMinutes()
                : servico.getValue() == null ? 50 : servico.getValue().getDuracaoMin();
        Spinner<Integer> duracao = new Spinner<>(1, AgendaService.DURACAO_MAXIMA_MIN, duracaoInicial, 5);
        duracao.setEditable(true);
        duracao.setPrefWidth(100);
        // trocar o servico sugere a duracao dele (depois de preencher, para a edicao manter a atual)
        servico.valueProperty().addListener((o, antes, s) -> {
            if (s != null) duracao.getValueFactory().setValue(s.getDuracaoMin());
        });
        ComboBox<Modalidade> modalidade = new ComboBox<>();
        modalidade.getItems().setAll(Modalidade.values());
        modalidade.setValue(edicao ? existente.getModalidade() : Modalidade.PRESENCIAL);
        Ui.rotular(modalidade, Modalidade::rotulo);
        TextField observacao = new TextField(edicao && existente.getObservacao() != null ? existente.getObservacao() : "");
        observacao.setPromptText("Ex.: trazer guia do convênio");
        observacao.setPrefColumnCount(28);
        CheckBox convenio = new CheckBox("Cobrar pelo convênio do paciente");

        GridPane g = Ui.formulario();
        if (edicao) {
            g.addRow(0, new Label("Paciente"), new Label(existente.getPacienteNome()));
            g.addRow(1, new Label("Profissional"), new Label(existente.getProfissionalNome()));
        } else {
            g.addRow(0, new Label("Paciente"), paciente);
            g.addRow(1, new Label("Profissional"), prof);
        }
        g.addRow(2, new Label("Serviço"), servico);
        g.addRow(3, new Label("Data"), data);
        g.addRow(4, new Label("Hora (HH:mm)"), hora);
        g.addRow(5, new Label("Duração (min)"), duracao);
        g.addRow(6, new Label("Modalidade"), modalidade);
        g.addRow(7, new Label("Observação"), observacao);
        Label avisoObs = Ui.aviso("Observação administrativa, visível também à secretaria. "
                + "Não registre informação clínica aqui: o lugar dela é o prontuário (cifrado).");
        avisoObs.setMaxWidth(380);
        g.add(avisoObs, 1, 8);
        if (edicao) {
            Label avisoPaciente = Ui.aviso("Para trocar o paciente ou o profissional, cancele e crie outro agendamento."
                    + (existente.isPago() ? " Já pago: o valor não muda." : ""));
            avisoPaciente.setMaxWidth(380);
            g.add(avisoPaciente, 1, 9);
        } else {
            g.add(convenio, 1, 9);
        }

        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle(edicao ? "Editar agendamento" : "Novo agendamento");
        d.getDialogPane().setContent(g);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        // valida antes de fechar: se der erro, a janela continua aberta
        d.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            boolean ok = Ui.executar(() -> {
                if (servico.getValue() == null || (!edicao && (paciente.getValue() == null || prof.getValue() == null)))
                    throw new RegraNegocioException(edicao ? "Escolha o serviço." : "Preencha paciente, profissional e serviço.");
                LocalDateTime inicio = montarInicio(data.getValue(), hora.getText());
                int minutos = lerDuracao(duracao.getEditor().getText());
                if (edicao) {
                    ctx.agenda.editar(existente.getId(), servico.getValue().getId(), inicio, minutos,
                            modalidade.getValue(), observacao.getText());
                } else {
                    ctx.agenda.agendar(paciente.getValue().getId(), prof.getValue().getId(), servico.getValue().getId(),
                            inicio, minutos, modalidade.getValue(), convenio.isSelected(), observacao.getText());
                }
            });
            if (!ok) ev.consume();
        });
        d.showAndWait();
        if (data.getValue() != null) dia.setValue(data.getValue());
        carregar();
    }

    /** Texto digitado no Spinner (o valor so e confirmado com Enter): numero inteiro de minutos. */
    static int lerDuracao(String texto) {
        try {
            return Integer.parseInt(texto == null ? "" : texto.trim());
        } catch (NumberFormatException e) {
            throw new RegraNegocioException("Duração inválida. Informe os minutos, ex.: 50");
        }
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
