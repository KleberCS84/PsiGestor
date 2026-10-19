package br.com.kleber.psigestor.ui;

import br.com.kleber.psigestor.service.Contexto;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Layout principal: menu lateral + area de conteudo (inspirado no painel do sistema de referencia). */
public class TelaPrincipal {
    private final Contexto ctx;
    private final Runnable aoSair;
    private final BorderPane raiz = new BorderPane();
    private final List<Button> botoes = new ArrayList<>();

    public TelaPrincipal(Contexto ctx, Runnable aoSair) {
        this.ctx = ctx;
        this.aoSair = aoSair;
    }

    public Parent criar() {
        var usuario = ctx.getSessao().usuario();
        Label marca = new Label("PsiGestor");
        marca.getStyleClass().add("menu-marca");
        Label quem = new Label(usuario.getNome() + "\n" + (usuario.isPsicologo() ? "CRP " + usuario.getCrp() : usuario.getPapel().rotulo()));
        quem.getStyleClass().add("menu-usuario");

        VBox menu = new VBox(4, marca, quem);
        menu.getStyleClass().add("menu");
        menu.setPadding(new Insets(18, 10, 18, 10));
        menu.getChildren().addAll(
                item("Painel", () -> new PainelView(ctx).criar()),
                item("Agenda", () -> new AgendaView(ctx, this).criar()),
                item("Pacientes", () -> new PacientesView(ctx).criar()),
                item("Prontuário", () -> new ProntuarioView(ctx, null).criar()),
                item("Financeiro", () -> new FinanceiroView(ctx).criar()),
                item("Serviços & Convênios", () -> new CadastrosView(ctx).criar()),
                item("Equipe & Repasse", () -> new EquipeView(ctx).criar()),
                item("Privacidade (LGPD)", () -> new PrivacidadeView(ctx).criar()));
        Button trocarSenha = new Button("Trocar senha");
        trocarSenha.getStyleClass().add("menu-item");
        trocarSenha.setMaxWidth(Double.MAX_VALUE);
        trocarSenha.setOnAction(e -> new TrocaSenhaDialog(ctx).mostrar());
        VBox.setMargin(trocarSenha, new Insets(20, 0, 0, 0));
        Button sobre = new Button("Sobre");
        sobre.getStyleClass().add("menu-item");
        sobre.setMaxWidth(Double.MAX_VALUE);
        sobre.setOnAction(e -> SobreDialog.mostrar());
        Button sair = new Button("Sair");
        sair.getStyleClass().add("menu-sair");
        sair.setMaxWidth(Double.MAX_VALUE);
        sair.setOnAction(e -> aoSair.run());
        menu.getChildren().addAll(trocarSenha, sobre, sair);

        raiz.setLeft(menu);
        botoes.get(0).fire();
        return raiz;
    }

    private Button item(String texto, Supplier<Node> tela) {
        Button b = new Button(texto);
        b.getStyleClass().add("menu-item");
        b.setMaxWidth(Double.MAX_VALUE);
        b.setOnAction(e -> {
            botoes.forEach(x -> x.getStyleClass().remove("ativo"));
            b.getStyleClass().add("ativo");
            // criar() de cada tela le o banco: erro ali vira aviso em vez de janela em branco
            Ui.executar(() -> mostrar(tela.get()));
        });
        botoes.add(b);
        return b;
    }

    void mostrar(Node conteudo) {
        ScrollPane sp = new ScrollPane(conteudo);
        sp.setFitToWidth(true);
        sp.setFitToHeight(true);
        raiz.setCenter(new HBox(sp));
        HBox.setHgrow(sp, javafx.scene.layout.Priority.ALWAYS);
    }

    /** Atalho usado pela Agenda: abre o prontuario ja no paciente escolhido. */
    void abrirProntuario(int pacienteId) {
        botoes.forEach(x -> x.getStyleClass().remove("ativo"));
        botoes.get(3).getStyleClass().add("ativo");
        Ui.executar(() -> mostrar(new ProntuarioView(ctx, pacienteId).criar()));
    }
}
