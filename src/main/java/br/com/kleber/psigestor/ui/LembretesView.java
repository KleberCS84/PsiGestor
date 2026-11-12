package br.com.kleber.psigestor.ui;

import br.com.kleber.psigestor.model.Atendimento;
import br.com.kleber.psigestor.service.Contexto;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;

import java.util.HashMap;
import java.util.Map;

/**
 * "Lembretes pendentes": sessoes das proximas 24h ainda nao lembradas. "Enviar lembrete" abre o WhatsApp com a
 * mensagem pronta (a psicologa confirma o envio la) e marca como lembrado; "Marcar como lembrado" so marca.
 * O envio so vale com CONTATO_WHATSAPP_EMAIL; o service confere de novo.
 */
public class LembretesView {
    static final String SEM_CONSENTIMENTO = "Este paciente não autorizou contato por WhatsApp/e-mail. "
            + "Registre o consentimento em Pacientes > Consentimentos para enviar; ou use \"Marcar como lembrado\" "
            + "se lembrou por outro meio.";

    private final Contexto ctx;
    private final Runnable aoAlterar;
    private final TableView<Atendimento> tabela = Ui.tabela();
    /** Por atendimento: o paciente autorizou contato (lido ao carregar, nao a cada clique). */
    private final Map<Integer, Boolean> consentimento = new HashMap<>();
    private final Button enviar = new Button("Enviar lembrete (WhatsApp)");
    private final Button marcar = new Button("Marcar como lembrado");
    private final Label total = new Label();
    private final Label semConsentimento = Ui.aviso(SEM_CONSENTIMENTO);

    /** aoAlterar: depois de cada carga (atualiza o contador do menu); pode ser null. */
    public LembretesView(Contexto ctx, Runnable aoAlterar) {
        this.ctx = ctx;
        this.aoAlterar = aoAlterar;
    }

    public Node criar() {
        tabela.getColumns().add(Ui.col("Quando", a -> quando(a), 80));
        tabela.getColumns().add(Ui.col("Data", a -> a.getInicio().format(Ui.DATA), 100));
        tabela.getColumns().add(Ui.col("Hora", a -> a.getInicio().format(Ui.HORA), 70));
        tabela.getColumns().add(Ui.col("Paciente", Atendimento::getPacienteNome, 200));
        tabela.getColumns().add(Ui.col("Profissional", Atendimento::getProfissionalNome, 170));
        tabela.getColumns().add(Ui.col("Modalidade", a -> a.getModalidade().rotulo(), 100));
        tabela.getColumns().add(Ui.col("Status", a -> a.getStatus().rotulo(), 100));
        tabela.getColumns().add(Ui.col("Contato autorizado", a -> autorizado(a) ? "Sim" : "Não", 130));
        tabela.getSelectionModel().selectedItemProperty().addListener((o, antes, a) -> atualizarBotoes());

        enviar.getStyleClass().add("primario");
        enviar.setOnAction(e -> Ui.selecionado(tabela).ifPresent(this::enviar));
        marcar.setOnAction(e -> Ui.selecionado(tabela).ifPresent(this::marcar));
        carregar();
        return Ui.tela(Ui.titulo("Lembretes pendentes"),
                Ui.aviso("Sessões das próximas 24 horas, agendadas ou confirmadas, cujo paciente ainda não foi lembrado. "
                        + "O WhatsApp abre com a mensagem pronta; você confere e confirma o envio por lá."),
                tabela, Ui.linha(total), semConsentimento, Ui.linha(enviar, marcar));
    }

    private String quando(Atendimento a) {
        return a.getInicio().toLocalDate().equals(ctx.envioAgenda.hoje()) ? "hoje" : "amanhã";
    }

    private boolean autorizado(Atendimento a) { return consentimento.getOrDefault(a.getId(), false); }

    private void carregar() {
        boolean ok = Ui.executar(() -> {
            var lista = ctx.lembretes.pendentes();
            consentimento.clear();
            for (Atendimento a : lista) consentimento.put(a.getId(), ctx.lembretes.temConsentimento(a.getPacienteId()));
            tabela.getItems().setAll(lista);
        });
        if (!ok) tabela.getItems().clear();
        int n = tabela.getItems().size();
        total.setText(n + (n == 1 ? " lembrete pendente" : " lembretes pendentes"));
        atualizarBotoes();
        if (aoAlterar != null) aoAlterar.run();
    }

    private void atualizarBotoes() {
        Atendimento a = tabela.getSelectionModel().getSelectedItem();
        boolean sem = a != null && !autorizado(a);
        enviar.setDisable(a == null || sem);
        marcar.setDisable(a == null);
        enviar.setTooltip(sem ? new Tooltip("Paciente sem consentimento de contato.") : null);
        semConsentimento.setVisible(sem);
        semConsentimento.setManaged(sem);
    }

    private void enviar(Atendimento a) {
        Ui.executar(() -> ConsultaAgendaView.abrirNoSistema(ctx.lembretes.enviarWhatsApp(a.getId()), false));
        carregar();   // se marcou, sai da lista; se deu erro, continua la
    }

    private void marcar(Atendimento a) {
        Ui.executar(() -> ctx.lembretes.marcarComoLembrado(a.getId()));
        carregar();
    }
}
