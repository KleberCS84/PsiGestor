package br.com.kleber.psigestor.ui;

import br.com.kleber.psigestor.model.Consentimento;
import br.com.kleber.psigestor.model.Convenio;
import br.com.kleber.psigestor.model.Paciente;
import br.com.kleber.psigestor.model.TipoConsentimento;
import br.com.kleber.psigestor.service.Contexto;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.time.LocalDate;
import java.util.Objects;

public class PacientesView {
    private final Contexto ctx;
    private final TableView<Paciente> tabela = Ui.tabela();
    private final TextField busca = new TextField();
    private final CheckBox arquivados = new CheckBox("Mostrar arquivados");

    public PacientesView(Contexto ctx) { this.ctx = ctx; }

    public Node criar() {
        busca.setPromptText("Buscar por nome...");
        busca.textProperty().addListener((o, a, n) -> carregar());
        arquivados.setOnAction(e -> carregar());
        Button novo = new Button("+ Novo paciente");
        novo.getStyleClass().add("primario");
        novo.setOnAction(e -> formulario(new Paciente()));

        tabela.getColumns().add(Ui.col("Nome", Paciente::getNome, 240));
        tabela.getColumns().add(Ui.col("Idade", p -> p.idadeEm(LocalDate.now()), 60));
        tabela.getColumns().add(Ui.col("Telefone", Paciente::getTelefone, 130));
        tabela.getColumns().add(Ui.col("E-mail", Paciente::getEmail, 180));
        tabela.getColumns().add(Ui.col("Responsavel", Paciente::getResponsavelLegal, 150));
        tabela.getColumns().add(Ui.col("Situacao", p -> p.isArquivado() ? "Arquivado" : "Ativo", 90));
        tabela.setRowFactory(t -> {
            TableRow<Paciente> r = new TableRow<>();
            r.setOnMouseClicked(e -> { if (e.getClickCount() == 2 && !r.isEmpty()) formulario(r.getItem()); });
            return r;
        });

        Button editar = new Button("Editar");
        editar.setOnAction(e -> Ui.selecionado(tabela).ifPresent(this::formulario));
        Button arquivar = new Button("Arquivar / Reativar");
        arquivar.setOnAction(e -> Ui.selecionado(tabela).ifPresent(p -> {
            String msg = p.isArquivado() ? "Reativar " + p.getNome() + "?"
                    : "Arquivar " + p.getNome() + "?\nO cadastro e o prontuario sao mantidos (guarda minima de 5 anos).";
            if (Ui.confirmar(msg)) Ui.executar(() -> ctx.pacienteService.arquivar(p.getId(), !p.isArquivado()));
            carregar();
        }));
        Button consent = new Button("Consentimentos");
        consent.setOnAction(e -> Ui.selecionado(tabela).ifPresent(this::consentimentos));
        Button exportar = new Button("Exportar dados (LGPD)");
        exportar.setOnAction(e -> Ui.selecionado(tabela).ifPresent(this::exportar));

        carregar();
        return Ui.tela(Ui.titulo("Pacientes"),
                Ui.linha(busca, arquivados, Ui.espaco(), novo),
                tabela,
                Ui.linha(editar, arquivar, consent, exportar),
                Ui.aviso("Nao ha botao de excluir: o registro documental deve ser guardado por no minimo 5 anos "
                        + "(Res. CFP 01/2009). Use \"Arquivar\" para tirar o paciente das listas."));
    }

    private void carregar() {
        tabela.getItems().setAll(ctx.pacienteService.pesquisar(busca.getText(), arquivados.isSelected()));
    }

    private void formulario(Paciente p) {
        TextField nome = new TextField(p.getNome());
        TextField cpf = new TextField(p.getCpf());
        cpf.setPromptText("opcional");
        DatePicker nasc = new DatePicker(p.getDataNascimento());
        TextField tel = new TextField(p.getTelefone());
        TextField email = new TextField(p.getEmail());
        TextField emerg = new TextField(p.getContatoEmergencia());
        TextField resp = new TextField(p.getResponsavelLegal());
        resp.setPromptText("obrigatorio se menor de 18");
        ComboBox<Convenio> conv = new ComboBox<>();
        conv.getItems().add(null);
        conv.getItems().addAll(ctx.convenios.listar());
        conv.getItems().stream().filter(c -> c != null && Objects.equals(c.getId(), p.getConvenioId()))
                .findFirst().ifPresent(conv::setValue);
        conv.setPromptText("Particular");
        TextField cart = new TextField(p.getNumeroCarteirinha());

        GridPane g = Ui.formulario();
        g.addRow(0, new Label("Nome *"), nome);
        g.addRow(1, new Label("CPF"), cpf);
        g.addRow(2, new Label("Nascimento"), nasc);
        g.addRow(3, new Label("Telefone"), tel);
        g.addRow(4, new Label("E-mail"), email);
        g.addRow(5, new Label("Contato de emergencia"), emerg);
        g.addRow(6, new Label("Responsavel legal"), resp);
        g.addRow(7, new Label("Convenio"), conv);
        g.addRow(8, new Label("N. carteirinha"), cart);
        Label dica = new Label("Colete so o necessario (LGPD art. 6, III - minimizacao).");
        dica.getStyleClass().add("dica");
        g.add(dica, 0, 9, 2, 1);

        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle(p.getId() == null ? "Novo paciente" : "Editar paciente");
        d.getDialogPane().setContent(g);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(ActionEvent.ACTION, ev -> {
            p.setNome(nome.getText());
            p.setCpf(cpf.getText());
            p.setDataNascimento(nasc.getValue());
            p.setTelefone(tel.getText());
            p.setEmail(email.getText());
            p.setContatoEmergencia(emerg.getText());
            p.setResponsavelLegal(resp.getText());
            p.setConvenioId(conv.getValue() == null ? null : conv.getValue().getId());
            p.setNumeroCarteirinha(cart.getText());
            if (!Ui.executar(() -> ctx.pacienteService.salvar(p))) ev.consume();
        });
        d.showAndWait();
        carregar();
    }

    private void consentimentos(Paciente p) {
        TableView<Consentimento> hist = Ui.tabela();
        hist.getColumns().add(Ui.col("Data", c -> c.registradoEm().format(Ui.DATA_HORA), 130));
        hist.getColumns().add(Ui.col("Tipo", c -> c.tipo().getDescricao(), 260));
        hist.getColumns().add(Ui.col("Situacao", c -> c.concedido() ? "Concedido" : "Negado/Revogado", 120));
        hist.getColumns().add(Ui.col("Obs.", Consentimento::observacao, 150));
        hist.getItems().setAll(ctx.pacienteService.consentimentos(p.getId()));
        hist.setPrefSize(680, 220);

        ComboBox<TipoConsentimento> tipo = new ComboBox<>();
        tipo.getItems().setAll(TipoConsentimento.values());
        tipo.setValue(TipoConsentimento.CONTRATO_TERAPEUTICO);
        CheckBox concedido = new CheckBox("Concedido");
        concedido.setSelected(true);
        TextField obs = new TextField();
        obs.setPromptText("ex.: termo assinado em papel, arquivado na pasta");
        Button registrar = new Button("Registrar");
        registrar.getStyleClass().add("primario");
        registrar.setOnAction(e -> {
            Ui.executar(() -> ctx.pacienteService.registrarConsentimento(p.getId(), tipo.getValue(),
                    concedido.isSelected(), obs.getText()));
            hist.getItems().setAll(ctx.pacienteService.consentimentos(p.getId()));
            obs.clear();
        });
        VBox v = new VBox(10, hist, Ui.linha(tipo, concedido), obs, registrar,
                Ui.aviso("Historico nao e apagado: uma revogacao e um novo registro. Vale o mais recente."));
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Consentimentos - " + p.getNome());
        d.getDialogPane().setContent(v);
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        d.showAndWait();
    }

    private void exportar(Paciente p) {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Onde salvar o relatorio de dados do paciente?");
        File pasta = dc.showDialog(tabela.getScene().getWindow());
        if (pasta == null) return;
        Ui.executar(() -> {
            var arq = ctx.pacienteService.exportarDadosDoTitular(p.getId(), pasta.toPath());
            Ui.info("Arquivo gerado:\n" + arq + "\n\nEntregue somente ao titular (ou responsavel legal).");
        });
    }

}
