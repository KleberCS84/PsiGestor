package br.com.kleber.psigestor.ui;

import br.com.kleber.psigestor.model.LogAcesso;
import br.com.kleber.psigestor.service.AutenticacaoService;
import br.com.kleber.psigestor.service.BackupService;
import br.com.kleber.psigestor.service.Contexto;
import br.com.kleber.psigestor.util.LogTecnico;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

public class PrivacidadeView {
    private final Contexto ctx;

    /** Tudo o que volta ao estado da data do backup e que costuma surpreender (M-7). */
    static final String AVISO_RESTAURACAO = """
            Os dados atuais serao SUBSTITUIDOS pelos do backup.
            Uma copia do banco atual sera mantida na pasta do PsiGestor.

            Atencao, tudo volta a ser como era na data do backup:
             - as senhas dos usuarios voltam a ser as vigentes naquela data;
             - usuarios desativados depois do backup voltam a ficar ATIVOS e conseguem entrar;
             - pacientes anonimizados depois do backup voltam com os dados pessoais.
            Depois de restaurar, revise a Equipe (desative de novo quem saiu) e refaca as anonimizacoes.

            Continuar?""";

    public PrivacidadeView(Contexto ctx) { this.ctx = ctx; }

    /** A copia antes-restauracao e o banco inteiro em claro: o usuario precisa saber onde ela esta (M-3). */
    private static void avisarCopiaAnterior(Path copia) {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.setTitle("PsiGestor");
        a.setHeaderText("Backup restaurado");
        a.setContentText("O banco anterior foi guardado SEM criptografia em:\n" + copia
                + "\n\nEle contem os dados dos pacientes em texto legivel. Guarde-o em local seguro ou apague-o "
                + "quando tiver certeza de que a restauracao esta correta.\n\n"
                + "O PsiGestor sera fechado. Abra-o novamente para continuar.");
        ButtonType abrirPasta = new ButtonType("Abrir pasta");
        a.getButtonTypes().setAll(abrirPasta, ButtonType.OK);
        if (a.showAndWait().orElse(ButtonType.OK) == abrirPasta) abrirPasta(copia.getParent());
    }

    private static void abrirPasta(Path pasta) {
        try {
            Desktop.getDesktop().open(pasta.toFile());
        } catch (IOException | UnsupportedOperationException e) {
            LogTecnico.erro("Nao foi possivel abrir a pasta da copia", e);
            Ui.info("Abra manualmente a pasta:\n" + pasta);
        }
    }

    public Node criar() {
        TableView<LogAcesso> log = Ui.tabela();
        log.getColumns().add(Ui.col("Data/hora", LogAcesso::dataHora, 150));
        log.getColumns().add(Ui.col("Usuario", LogAcesso::usuario, 180));
        log.getColumns().add(Ui.col("Acao", LogAcesso::acao, 220));
        log.getColumns().add(Ui.col("Entidade", LogAcesso::entidade, 110));
        log.getColumns().add(Ui.col("Id", LogAcesso::entidadeId, 60));
        log.getColumns().add(Ui.col("Detalhe", LogAcesso::detalhe, 180));
        Ui.executar(() -> log.getItems().setAll(ctx.logs.ultimos(300)));

        Button backup = new Button("Gerar backup agora");
        backup.getStyleClass().add("primario");
        backup.setDisable(!ctx.getSessao().usuario().isPsicologo());
        backup.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Pasta do backup (prefira um pendrive/disco criptografado)");
            File pasta = dc.showDialog(log.getScene().getWindow());
            if (pasta == null) return;
            SenhaBackupDialog.pedir("Senha do backup", "Minimo de " + AutenticacaoService.SENHA_MINIMA
                    + " caracteres. A senha NAO e guardada: sem ela o backup nao pode ser restaurado. "
                    + "Anote-a em local seguro, separado do backup.", true)
                    .ifPresent(s -> Ui.executar(() -> Ui.info("Backup cifrado salvo em:\n"
                            + ctx.backup.gerar(pasta.toPath(), s.senha(), s.confirmacao()))));
        });

        Button restaurar = new Button("Restaurar backup...");
        restaurar.setDisable(!ctx.getSessao().usuario().isPsicologo());
        restaurar.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Escolha o arquivo de backup");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Backup do PsiGestor", "*" + BackupService.EXTENSAO));
            File arq = fc.showOpenDialog(log.getScene().getWindow());
            if (arq == null) return;
            SenhaBackupDialog.pedir("Restaurar backup", "Informe a senha usada ao gerar este backup.", false)
                    .ifPresent(s -> {
                        if (!Ui.confirmar(AVISO_RESTAURACAO)) {
                            java.util.Arrays.fill(s.senha(), '\0');
                            return;
                        }
                        boolean ok = Ui.executar(() -> avisarCopiaAnterior(ctx.backup.restaurar(arq.toPath(), s.senha())));
                        // apos restaurar (ou falhar depois de fechar o banco) o contexto nao serve mais
                        if (ok || ctx.getSessao() == null) Platform.exit();
                    });
        });

        Label checklist = new Label("""
                Checklist rapido (detalhes em docs/PONTOS_DE_ATENCAO_LEGAIS.md):
                 - Dados de saude sao dados pessoais SENSIVEIS (LGPD art. 5, II e art. 11).
                 - Ative a criptografia de disco do computador (BitLocker no Windows) e use senha forte.
                 - Faca backup periodico e guarde em local seguro; teste a restauracao.
                 - Guarde o registro documental por no minimo 5 anos (Res. CFP 01/2009).
                 - Atendimento online: cadastro no e-Psi (Res. CFP 11/2018) e plataforma segura.
                 - Incidente de seguranca com risco ao paciente: avaliar comunicacao a ANPD e ao titular (LGPD art. 48).""");
        checklist.setWrapText(true);
        checklist.getStyleClass().add("aviso");
        checklist.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);

        return Ui.tela(Ui.titulo("Privacidade & Seguranca"), checklist, Ui.linha(backup, restaurar),
                Ui.titulo("Log de acesso (somente leitura)"), log);
    }
}
