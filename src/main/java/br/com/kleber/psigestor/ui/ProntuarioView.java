package br.com.kleber.psigestor.ui;

import br.com.kleber.psigestor.model.Evolucao;
import br.com.kleber.psigestor.model.Paciente;
import br.com.kleber.psigestor.service.Contexto;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

public class ProntuarioView {
    private final Contexto ctx;
    private final Integer pacienteInicial;
    private final ComboBox<Paciente> paciente = new ComboBox<>();
    private final ListView<Evolucao> registros = new ListView<>();
    private final TextArea texto = new TextArea();

    public ProntuarioView(Contexto ctx, Integer pacienteInicial) {
        this.ctx = ctx;
        this.pacienteInicial = pacienteInicial;
    }

    public Node criar() {
        if (!ctx.getSessao().podeLerProntuario()) {
            return Ui.tela(Ui.titulo("Prontuario"), Ui.aviso(
                    "Acesso restrito ao psicologo. O conteudo do prontuario e sigiloso (Codigo de Etica "
                    + "Profissional do Psicologo, art. 9) e fica cifrado com a chave pessoal do profissional."));
        }
        Ui.executar(() -> paciente.getItems().setAll(ctx.pacienteService.pesquisar("", true)));
        paciente.setPromptText("Escolha o paciente");
        paciente.setPrefWidth(320);
        paciente.setOnAction(e -> carregar());
        if (pacienteInicial != null) {
            paciente.getItems().stream().filter(p -> p.getId() == pacienteInicial.intValue())
                    .findFirst().ifPresent(paciente::setValue);
        }

        registros.setCellFactory(l -> new ListCell<>() {
            @Override protected void updateItem(Evolucao ev, boolean vazio) {
                super.updateItem(ev, vazio);
                if (vazio || ev == null) { setText(null); return; }
                String cab = ev.registradoEm().format(Ui.DATA_HORA) + "  #" + ev.id()
                        + (ev.isRetificacao() ? "  (RETIFICA o registro #" + ev.retificaId() + ")" : "");
                setText(cab + "\n" + ev.texto());
                setWrapText(true);
                setPrefWidth(0);
            }
        });
        VBox.setVgrow(registros, Priority.ALWAYS);

        texto.setPromptText("Evolucao da sessao: demanda, procedimentos/tecnicas, evolucao, encaminhamentos...");
        texto.setPrefRowCount(5);
        texto.setWrapText(true);
        Button salvar = new Button("Salvar registro");
        salvar.getStyleClass().add("primario");
        salvar.setOnAction(e -> {
            if (paciente.getValue() == null) { Ui.erro("Escolha o paciente."); return; }
            if (!Ui.confirmar("Depois de salvo o registro NAO pode ser editado nem apagado, apenas retificado. Salvar?")) return;
            if (Ui.executar(() -> ctx.prontuario.registrar(paciente.getValue().getId(), null, texto.getText()))) {
                texto.clear();
                carregar();
            }
        });
        Button retificar = new Button("Retificar selecionado");
        retificar.setOnAction(e -> retificar());

        carregar();
        return Ui.tela(Ui.titulo("Prontuario / Registro documental"),
                Ui.linha(new Label("Paciente:"), paciente),
                registros,
                new Label("Novo registro"), texto,
                Ui.linha(salvar, retificar),
                Ui.aviso("Registros sao cifrados (AES-256) com a sua senha e ficam registrados no log de acesso. "
                        + "Registre so o necessario para a continuidade do cuidado (Res. CFP 01/2009). "
                        + "Correcoes geram um novo registro de retificacao, mantendo o original."));
    }

    private void carregar() {
        if (paciente.getValue() == null) { registros.getItems().clear(); return; }
        Ui.executar(() -> registros.getItems().setAll(ctx.prontuario.listar(paciente.getValue().getId())));
    }

    private void retificar() {
        Evolucao ev = registros.getSelectionModel().getSelectedItem();
        if (ev == null) { Ui.erro("Selecione o registro a retificar."); return; }
        TextInputDialog d = new TextInputDialog();
        d.setTitle("Retificacao do registro #" + ev.id());
        d.setHeaderText("Descreva a correcao (o registro original sera mantido).");
        d.showAndWait().ifPresent(t -> {
            Ui.executar(() -> ctx.prontuario.retificar(ev.pacienteId(), ev.id(), t));
            carregar();
        });
    }
}
