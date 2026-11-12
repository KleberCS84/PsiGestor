package br.com.kleber.psigestor.ui;

import br.com.kleber.psigestor.model.Consentimento;
import br.com.kleber.psigestor.model.Convenio;
import br.com.kleber.psigestor.model.Paciente;
import br.com.kleber.psigestor.model.TipoConsentimento;
import br.com.kleber.psigestor.service.Contexto;
import br.com.kleber.psigestor.service.PacienteService;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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
        tabela.getColumns().add(Ui.col("Responsável", Paciente::getResponsavelLegal, 150));
        tabela.getColumns().add(Ui.col("Situação", p -> p.isArquivado() ? "Arquivado" : "Ativo", 90));
        tabela.setRowFactory(t -> {
            TableRow<Paciente> r = new TableRow<>();
            r.setOnMouseClicked(e -> { if (e.getClickCount() == 2 && !r.isEmpty()) formulario(r.getItem()); });
            return r;
        });

        Button editar = new Button("Editar");
        editar.setOnAction(e -> Ui.selecionado(tabela).ifPresent(this::formulario));
        Button arquivar = new Button("Arquivar / Reativar");
        arquivar.setOnAction(e -> Ui.selecionado(tabela).ifPresent(p -> {
            if (p.isArquivado()) reativar(p);
            else arquivar(p, null, "Arquivar " + p.getNome() + "?");
        }));
        Button excluir = new Button("Excluir");
        excluir.setOnAction(e -> Ui.selecionado(tabela).ifPresent(this::excluir));
        Button consent = new Button("Consentimentos");
        consent.setOnAction(e -> Ui.selecionado(tabela).ifPresent(this::consentimentos));
        Button exportar = new Button("Exportar dados (LGPD)");
        exportar.setDisable(!ctx.usuarioLogado().isPsicologo());
        exportar.setOnAction(e -> Ui.selecionado(tabela).ifPresent(this::exportar));
        Button anonimizar = new Button("Anonimizar (LGPD)");
        anonimizar.setOnAction(e -> Ui.selecionado(tabela).ifPresent(this::anonimizar));

        carregar();
        return Ui.tela(Ui.titulo("Pacientes"),
                Ui.linha(busca, arquivados, Ui.espaco(), novo),
                tabela,
                Ui.linha(editar, arquivar, excluir, consent, exportar, anonimizar),
                Ui.aviso("Só é possível excluir paciente sem sessão realizada, prontuário nem financeiro. Com histórico, "
                        + "use \"Arquivar\": o prontuário deve ser guardado por no mínimo 5 anos (Res. CFP 01/2009)."));
    }

    private void reativar(Paciente p) {
        if (Ui.confirmar("Reativar " + p.getNome() + "?")) Ui.executar(() -> ctx.pacienteService.arquivar(p.getId(), false));
        carregar();
    }

    /**
     * Arquiva (nao apaga). h null = consulta o historico aqui (so para saber dos agendamentos futuros).
     * A pergunta inicial muda conforme quem chamou (botao Arquivar ou aviso da exclusao).
     */
    private void arquivar(Paciente p, PacienteService.HistoricoPaciente h, String pergunta) {
        PacienteService.HistoricoPaciente[] historico = {h};
        if (h == null && !Ui.executar(() -> historico[0] = ctx.pacienteService.historico(p.getId()))) return;
        if (!Ui.confirmar(pergunta + "\n\nO cadastro e o prontuário são mantidos: o prontuário precisa ser guardado "
                + "por no mínimo 5 anos (Res. CFP 01/2009). O paciente some das listas, buscas e agendamentos; "
                + "para vê-lo ou reativá-lo, marque \"Mostrar arquivados\".")) return;
        int futuros = historico[0].agendamentosFuturos();
        boolean cancelar = futuros > 0 && Ui.confirmar(p.getNome() + " tem " + futuros + " agendamento(s) futuro(s).\n"
                + "Cancelar esses agendamentos também?\n(Sim: cancela e arquiva. Não: arquiva e mantém os agendamentos.)");
        Ui.executar(() -> ctx.pacienteService.arquivar(p.getId(), true, cancelar));
        carregar();
    }

    /** Sem historico: exclusao definitiva. Com historico: explica e oferece arquivar. */
    private void excluir(Paciente p) {
        PacienteService.HistoricoPaciente[] historico = new PacienteService.HistoricoPaciente[1];
        if (!Ui.executar(() -> historico[0] = ctx.pacienteService.historico(p.getId()))) return;
        PacienteService.HistoricoPaciente h = historico[0];
        if (h.temHistorico()) {
            List<String> motivos = new ArrayList<>();
            if (h.atendimentosRealizados() > 0) motivos.add(h.atendimentosRealizados() + " atendimento(s) realizado(s)");
            if (h.registrosProntuario() > 0) motivos.add(h.registrosProntuario() + " registro(s) de prontuário");
            if (h.lancamentosFinanceiros() > 0) motivos.add(h.lancamentosFinanceiros() + " lançamento(s) financeiro(s)");
            if (p.isArquivado()) {
                Ui.info(p.getNome() + " tem histórico (" + String.join(", ", motivos) + ") e não pode ser excluído.\n"
                        + "O cadastro já está arquivado; o prontuário precisa ser guardado por no mínimo 5 anos "
                        + "(Res. CFP 01/2009).");
                return;
            }
            arquivar(p, h, p.getNome() + " tem histórico (" + String.join(", ", motivos) + ") e não pode ser excluído.\n"
                    + "Deseja arquivar o cadastro?");
            return;
        }
        String extra = h.agendamentosFuturos() > 0
                ? "\nOs " + h.agendamentosFuturos() + " agendamento(s) futuro(s) e os consentimentos também serão apagados."
                : "\nOs consentimentos dele também serão apagados.";
        if (Ui.confirmar("Excluir permanentemente " + p.getNome() + "? Essa ação não pode ser desfeita." + extra))
            Ui.executar(() -> ctx.pacienteService.excluir(p.getId()));
        carregar();
    }

    /** Irreversivel: so prossegue se o nome do paciente for digitado exatamente. */
    private void anonimizar(Paciente p) {
        TextInputDialog d = new TextInputDialog();
        d.setTitle("Anonimizar paciente");
        d.setHeaderText("Esta ação NÃO pode ser desfeita.");
        d.setContentText("Nome, CPF, nascimento, telefone, e-mail e contato de emergência serão substituídos.\n"
                + "Atendimentos, financeiro e prontuário são mantidos.\n\n"
                + "Digite o nome do paciente para confirmar:");
        String digitado = d.showAndWait().map(String::strip).orElse(null);
        if (digitado == null) return;
        if (!digitado.equals(p.getNome())) {
            Ui.erro("O nome digitado não confere. Nada foi alterado.");
            return;
        }
        if (Ui.executar(() -> ctx.pacienteService.anonimizarPaciente(p.getId())))
            Ui.info("Paciente anonimizado.");
        carregar();
    }

    private void carregar() {
        Ui.executar(() -> tabela.getItems().setAll(ctx.pacienteService.pesquisar(busca.getText(), arquivados.isSelected())));
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
        resp.setPromptText("obrigatório se menor de 18");
        ComboBox<Convenio> conv = new ComboBox<>();
        conv.getItems().add(null);
        if (!Ui.executar(() -> conv.getItems().addAll(ctx.cadastros.listarConvenios()))) return;
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
        g.addRow(5, new Label("Contato de emergência"), emerg);
        g.addRow(6, new Label("Responsável legal"), resp);
        g.addRow(7, new Label("Convênio"), conv);
        g.addRow(8, new Label("Nº da carteirinha"), cart);
        Label dica = new Label("Colete só o necessário (LGPD art. 6º, III - minimização).");
        dica.getStyleClass().add("dica");
        g.add(dica, 0, 9, 2, 1);

        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle(p.getId() == null ? "Novo paciente" : "Editar paciente");
        d.getDialogPane().setContent(g);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(ActionEvent.ACTION, ev -> {
            // edita uma copia: se a validacao falhar, a linha da tabela nao mostra dados nao salvos
            Paciente edicao = rascunho(p);
            edicao.setNome(nome.getText());
            edicao.setCpf(cpf.getText());
            edicao.setDataNascimento(nasc.getValue());
            edicao.setTelefone(tel.getText());
            edicao.setEmail(email.getText());
            edicao.setContatoEmergencia(emerg.getText());
            edicao.setResponsavelLegal(resp.getText());
            edicao.setConvenioId(conv.getValue() == null ? null : conv.getValue().getId());
            edicao.setNumeroCarteirinha(cart.getText());
            if (!Ui.executar(() -> ctx.pacienteService.salvar(edicao))) ev.consume();
        });
        d.showAndWait();
        carregar();   // a tabela so passa a mostrar o que foi gravado no banco
    }

    /** Copia completa (inclusive id e arquivado) para o formulario editar sem tocar na linha da tabela. */
    static Paciente rascunho(Paciente p) {
        Paciente c = new Paciente(p.getNome(), p.getDataNascimento(), p.getTelefone());
        c.setId(p.getId());
        c.setCpf(p.getCpf());
        c.setEmail(p.getEmail());
        c.setContatoEmergencia(p.getContatoEmergencia());
        c.setResponsavelLegal(p.getResponsavelLegal());
        c.setConvenioId(p.getConvenioId());
        c.setNumeroCarteirinha(p.getNumeroCarteirinha());
        c.setArquivado(p.isArquivado());
        return c;
    }

    private void consentimentos(Paciente p) {
        TableView<Consentimento> hist = Ui.tabela();
        hist.getColumns().add(Ui.col("Data", c -> c.registradoEm().format(Ui.DATA_HORA), 130));
        hist.getColumns().add(Ui.col("Tipo", c -> c.tipo().getDescricao(), 260));
        hist.getColumns().add(Ui.col("Situação", c -> c.concedido() ? "Concedido" : "Negado/Revogado", 120));
        hist.getColumns().add(Ui.col("Obs.", Consentimento::observacao, 150));
        if (!Ui.executar(() -> hist.getItems().setAll(ctx.pacienteService.consentimentos(p.getId())))) return;
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
            Ui.executar(() -> hist.getItems().setAll(ctx.pacienteService.consentimentos(p.getId())));
            obs.clear();
        });
        VBox v = new VBox(10, hist, Ui.linha(tipo, concedido), obs, registrar,
                Ui.aviso("Histórico não é apagado: uma revogação é um novo registro. Vale o mais recente."));
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Consentimentos - " + p.getNome());
        d.getDialogPane().setContent(v);
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        d.showAndWait();
    }

    private void exportar(Paciente p) {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Onde salvar o relatório de dados do paciente?");
        File pasta = dc.showDialog(tabela.getScene().getWindow());
        if (pasta == null) return;
        Ui.executar(() -> {
            var arq = ctx.pacienteService.exportarDadosDoTitular(p.getId(), pasta.toPath());
            Ui.info("Arquivo gerado:\n" + arq + "\n\nEntregue somente ao titular (ou responsável legal).\n"
                    + "O arquivo NÃO é cifrado: apague-o deste computador (e da lixeira) depois de entregar.");
        });
    }

}
