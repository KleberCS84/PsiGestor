package br.com.kleber.psigestor.ui;

import br.com.kleber.psigestor.model.Atendimento;
import br.com.kleber.psigestor.model.Paciente;
import br.com.kleber.psigestor.model.StatusAtendimento;
import br.com.kleber.psigestor.model.TipoConsentimento;
import br.com.kleber.psigestor.service.Contexto;
import br.com.kleber.psigestor.service.EnvioAgendaService;
import br.com.kleber.psigestor.service.EnvioAgendaService.Canal;
import br.com.kleber.psigestor.service.EnvioAgendaService.EnvioAgenda;
import br.com.kleber.psigestor.service.EnvioAgendaService.PeriodoConsulta;
import br.com.kleber.psigestor.util.LogTecnico;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * "Consultar agenda": horarios de um paciente (ou visao geral do periodo) e envio ao paciente.
 * Os botoes de envio so valem com UM paciente escolhido; a mensagem e montada pelo service,
 * que rele do banco so os atendimentos desse paciente.
 */
public class ConsultaAgendaView {
    private final Contexto ctx;
    private final TableView<Atendimento> tabela = Ui.tabela();
    private final TextField busca = new TextField();
    private final ComboBox<Paciente> paciente = new ComboBox<>();
    private final ComboBox<PeriodoConsulta> periodo = new ComboBox<>();
    private final DatePicker de = new DatePicker();
    private final DatePicker ate = new DatePicker();
    private final ComboBox<StatusAtendimento> status = new ComboBox<>();
    private final Label total = new Label();
    private final TableColumn<Atendimento, String> colPaciente = Ui.col("Paciente", Atendimento::getPacienteNome, 180);
    private final List<Button> envios;
    /** De e Ate mudam um de cada vez: sem isto, o intervalo intermediario (invertido) daria erro. */
    private boolean ajustandoPeriodo;

    public ConsultaAgendaView(Contexto ctx) {
        this.ctx = ctx;
        envios = List.of(
                botaoEnvio("Enviar por WhatsApp", Canal.WHATSAPP),
                botaoEnvio("Enviar por e-mail", Canal.EMAIL),
                botaoEnvio("Copiar mensagem", Canal.COPIA),
                botaoEnvio("Salvar convite (.ics)", Canal.ICS));
    }

    public Node criar() {
        busca.setPromptText("Buscar paciente pelo nome");
        busca.textProperty().addListener((o, antes, texto) -> carregarPacientes(texto));
        paciente.setPromptText("Todos os pacientes");
        paciente.setPrefWidth(260);
        carregarPacientes("");
        paciente.setOnAction(e -> carregar());

        periodo.getItems().setAll(PeriodoConsulta.values());
        Ui.rotular(periodo, PeriodoConsulta::rotulo);
        periodo.setValue(PeriodoConsulta.PROXIMOS_30_DIAS);
        periodo.setOnAction(e -> aplicarPeriodo());
        de.setOnAction(e -> carregar());
        ate.setOnAction(e -> carregar());

        status.getItems().add(null);   // null = todos
        status.getItems().addAll(StatusAtendimento.values());
        Ui.rotular(status, StatusAtendimento::rotulo);
        status.setPromptText("Todos os status");
        status.setValue(StatusAtendimento.AGENDADO);
        status.setOnAction(e -> carregar());

        tabela.getColumns().add(Ui.col("Data", a -> a.getInicio().format(Ui.DATA), 100));
        tabela.getColumns().add(Ui.col("Dia", a -> EnvioAgendaService.diaDaSemana(a.getInicio().toLocalDate()), 60));
        tabela.getColumns().add(Ui.col("Hora", a -> a.getInicio().format(Ui.HORA), 70));
        tabela.getColumns().add(Ui.col("Duração", a -> EnvioAgendaService.duracaoMin(a) + " min", 80));
        tabela.getColumns().add(Ui.col("Modalidade", a -> a.getModalidade().rotulo(), 100));
        tabela.getColumns().add(Ui.col("Profissional", Atendimento::getProfissionalNome, 170));
        tabela.getColumns().add(Ui.col("Status", a -> a.getStatus().rotulo(), 100));
        tabela.getColumns().add(colPaciente);   // so na visao geral (sem paciente escolhido)

        aplicarPeriodo();
        return Ui.tela(Ui.titulo("Consultar agenda"),
                Ui.linha(new Label("Paciente:"), busca, paciente),
                Ui.linha(new Label("Período:"), periodo, de, new Label("até"), ate,
                        new Label("Status:"), status),
                tabela,
                Ui.linha(total),
                new VBox(6, Ui.linha(envios.toArray(Button[]::new)),
                        Ui.aviso("Para enviar, escolha UM paciente. A mensagem leva só os horários dele e exige "
                                + "o consentimento de contato por WhatsApp/e-mail (LGPD). Confira o texto antes de enviar.")));
    }

    private void carregarPacientes(String texto) {
        Paciente atual = paciente.getValue();
        Ui.executar(() -> {
            paciente.getItems().setAll((Paciente) null);
            paciente.getItems().addAll(ctx.pacienteService.pesquisar(texto == null ? "" : texto.trim(), false));
        });
        // continua no mesmo paciente se ele ainda aparece na busca
        paciente.setValue(atual == null ? null : paciente.getItems().stream()
                .filter(p -> p != null && p.getId().equals(atual.getId())).findFirst().orElse(null));
        carregar();
    }

    private void aplicarPeriodo() {
        PeriodoConsulta p = periodo.getValue();
        boolean personalizado = p == PeriodoConsulta.PERSONALIZADO;
        de.setDisable(!personalizado);
        ate.setDisable(!personalizado);
        if (p != null && !personalizado) {
            LocalDate[] intervalo = p.intervalo(ctx.envioAgenda.hoje());
            ajustandoPeriodo = true;
            try {
                de.setValue(intervalo[0]);
                ate.setValue(intervalo[1]);
            } finally {
                ajustandoPeriodo = false;
            }
        }
        carregar();
    }

    private void carregar() {
        if (ajustandoPeriodo || periodo.getValue() == null) return;   // montando a tela ou trocando o atalho
        Paciente p = paciente.getValue();
        colPaciente.setVisible(p == null);
        boolean ok = Ui.executar(() -> tabela.getItems().setAll(
                ctx.envioAgenda.consultar(p == null ? null : p.getId(), de.getValue(), ate.getValue(), status.getValue())));
        if (!ok) tabela.getItems().clear();
        int n = tabela.getItems().size();
        total.setText("Total: " + n + (n == 1 ? " atendimento" : " atendimentos"));
        envios.forEach(b -> b.setDisable(p == null || n == 0));
    }

    private Button botaoEnvio(String texto, Canal canal) {
        Button b = new Button(texto);
        b.setDisable(true);
        b.setOnAction(e -> enviar(canal));
        return b;
    }

    private void enviar(Canal canal) {
        Paciente p = paciente.getValue();
        if (p == null) return;
        if (!garantirConsentimento(p)) return;
        EnvioAgenda[] envio = {null};
        if (!Ui.executar(() -> {
            envio[0] = ctx.envioAgenda.preparar(p.getId(), de.getValue(), ate.getValue(), status.getValue());
            ctx.envioAgenda.verificar(envio[0], canal);   // telefone/e-mail errado: avisa antes da previa
        })) return;
        EnvioAgenda e = envio[0];
        switch (canal) {
            case WHATSAPP -> previa(e, "Abrir WhatsApp").ifPresent(t ->
                    Ui.executar(() -> abrirNoSistema(ctx.envioAgenda.enviarWhatsApp(e, t), false)));
            case EMAIL -> previa(e, "Abrir e-mail").ifPresent(t ->
                    Ui.executar(() -> abrirNoSistema(ctx.envioAgenda.enviarEmail(e, t), true)));
            case COPIA -> previa(e, "Copiar").ifPresent(t -> Ui.executar(() -> {
                ClipboardContent c = new ClipboardContent();
                c.putString(ctx.envioAgenda.copiar(e, t));
                Clipboard.getSystemClipboard().setContent(c);
                Ui.info("Mensagem copiada. Cole no canal combinado com o paciente.");
            }));
            case ICS -> salvarIcs(e);
        }
    }

    /** Sem CONTATO_WHATSAPP_EMAIL vigente pergunta na hora; "nao" cancela o envio sem registrar nada. */
    private boolean garantirConsentimento(Paciente p) {
        boolean[] tem = {false};
        if (!Ui.executar(() -> tem[0] = ctx.envioAgenda.temConsentimentoContato(p.getId()))) return false;
        if (tem[0]) return true;
        if (!Ui.confirmar("O paciente autoriza contato por WhatsApp e e-mail?")) return false;
        return Ui.executar(() -> ctx.pacienteService.registrarConsentimento(p.getId(),
                TipoConsentimento.CONTATO_WHATSAPP_EMAIL, true, "registrado no envio de horários"));
    }

    /** A psicologa confere (e pode ajustar) o texto; o que sair daqui e o que vai. */
    private Optional<String> previa(EnvioAgenda e, String acao) {
        TextArea texto = new TextArea(e.mensagem());
        texto.setWrapText(true);
        texto.setPrefRowCount(10);
        texto.setPrefColumnCount(48);
        ButtonType confirmar = new ButtonType(acao, ButtonBar.ButtonData.OK_DONE);
        Dialog<String> d = new Dialog<>();
        d.setTitle("Enviar horários");
        d.setHeaderText("Mensagem para " + e.paciente().getNome() + " (" + e.atendimentos().size()
                + (e.atendimentos().size() == 1 ? " atendimento)" : " atendimentos)"));
        d.getDialogPane().setContent(texto);
        d.getDialogPane().getButtonTypes().addAll(confirmar, ButtonType.CANCEL);
        d.getDialogPane().lookupButton(confirmar).disableProperty().bind(texto.textProperty().isEmpty());
        d.setResultConverter(b -> b == confirmar ? texto.getText() : null);
        return d.showAndWait().filter(t -> !t.isBlank());
    }

    private void salvarIcs(EnvioAgenda e) {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Onde salvar o convite (.ics)?");
        File pasta = dc.showDialog(tabela.getScene().getWindow());
        if (pasta == null) return;
        Ui.executar(() -> {
            Path arq = ctx.envioAgenda.salvarIcs(e, pasta.toPath());
            Ui.info("Convite salvo:\n" + arq + "\n\nAnexe ao e-mail ou à mensagem do paciente. "
                    + "Ele não contém dados clínicos, só data, hora e profissional.");
        });
    }

    /** Navegador/cliente de e-mail fora da thread do JavaFX (AWT e JavaFX nao podem se travar). */
    private static void abrirNoSistema(URI uri, boolean email) {
        Thread t = new Thread(() -> {
            try {
                if (!Desktop.isDesktopSupported()) throw new UnsupportedOperationException("Desktop indisponivel");
                Desktop d = Desktop.getDesktop();
                if (email && d.isSupported(Desktop.Action.MAIL)) d.mail(uri); else d.browse(uri);
            } catch (Exception ex) {
                LogTecnico.erro("Abrir link de envio de horarios", ex);
                Platform.runLater(() -> Ui.erro("Não foi possível abrir o navegador ou o e-mail. "
                        + "Use \"Copiar mensagem\" e cole no WhatsApp ou e-mail."));
            }
        }, "envio-horarios");
        t.setDaemon(true);
        t.start();
    }
}
