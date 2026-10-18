package br.com.kleber.psigestor.ui;

import br.com.kleber.psigestor.model.LogAcesso;
import br.com.kleber.psigestor.service.Contexto;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.stage.DirectoryChooser;

import java.io.File;

public class PrivacidadeView {
    private final Contexto ctx;

    public PrivacidadeView(Contexto ctx) { this.ctx = ctx; }

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
            if (pasta != null) Ui.executar(() -> Ui.info("Backup salvo em:\n" + ctx.backup.gerar(pasta.toPath())));
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

        return Ui.tela(Ui.titulo("Privacidade & Seguranca"), checklist, Ui.linha(backup),
                Ui.titulo("Log de acesso (somente leitura)"), log);
    }
}
