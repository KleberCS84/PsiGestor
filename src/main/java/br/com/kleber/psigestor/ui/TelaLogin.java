package br.com.kleber.psigestor.ui;

import br.com.kleber.psigestor.model.Papel;
import br.com.kleber.psigestor.service.Contexto;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

public class TelaLogin {
    private final Contexto ctx;
    private final Runnable aoEntrar;

    public TelaLogin(Contexto ctx, Runnable aoEntrar) {
        this.ctx = ctx;
        this.aoEntrar = aoEntrar;
    }

    public Parent criar() {
        VBox caixa = ctx.autenticacao.primeiroAcesso() ? primeiroAcesso() : login();
        caixa.getStyleClass().add("login-caixa");
        caixa.setMaxWidth(460);
        caixa.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        StackPane fundo = new StackPane(caixa);
        fundo.getStyleClass().add("login-fundo");
        return fundo;
    }

    private VBox login() {
        TextField login = new TextField();
        login.setPromptText("login");
        PasswordField senha = new PasswordField();
        senha.setPromptText("senha");
        Button entrar = new Button("Entrar");
        entrar.setDefaultButton(true);
        entrar.getStyleClass().add("primario");
        entrar.setMaxWidth(Double.MAX_VALUE);
        entrar.setOnAction(e -> {
            if (Ui.executar(() -> ctx.autenticacao.entrar(login.getText(), senha.getText().toCharArray())))
                aoEntrar.run();
            senha.clear();
        });
        Label marca = new Label("PsiGestor");
        marca.getStyleClass().add("login-marca");
        VBox v = new VBox(12, marca, new Label("Gestao de consultorio com sigilo por padrao"), login, senha, entrar);
        v.setAlignment(Pos.CENTER_LEFT);
        return v;
    }

    private VBox primeiroAcesso() {
        TextField nome = new TextField();
        TextField crp = new TextField();
        crp.setPromptText("ex.: 09/12345");
        TextField login = new TextField();
        PasswordField senha = new PasswordField();
        PasswordField senha2 = new PasswordField();
        CheckBox ciente = new CheckBox("Entendo que o prontuario e cifrado com a minha senha:\n"
                + "se eu perder a senha, os registros nao podem ser recuperados.");
        ciente.setWrapText(true);
        ciente.setMaxWidth(390);
        ciente.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        GridPane g = Ui.formulario();
        g.addRow(0, new Label("Nome completo"), nome);
        g.addRow(1, new Label("CRP"), crp);
        g.addRow(2, new Label("Login"), login);
        g.addRow(3, new Label("Senha (min. 8)"), senha);
        g.addRow(4, new Label("Repita a senha"), senha2);
        Button criar = new Button("Criar conta do psicologo responsavel");
        criar.getStyleClass().add("primario");
        criar.setOnAction(e -> {
            if (!senha.getText().equals(senha2.getText())) { Ui.erro("As senhas nao conferem."); return; }
            if (!ciente.isSelected()) { Ui.erro("Marque a ciencia sobre a senha do prontuario."); return; }
            String s = senha.getText();
            boolean ok = Ui.executar(() -> {
                ctx.autenticacao.cadastrar(nome.getText(), login.getText(), s.toCharArray(), Papel.PSICOLOGO, crp.getText(), 0);
                ctx.autenticacao.entrar(login.getText(), s.toCharArray());
            });
            if (ok) aoEntrar.run();
        });
        Label marca = new Label("Bem-vindo ao PsiGestor");
        marca.getStyleClass().add("login-marca");
        return new VBox(10, marca, new Label("Primeiro acesso: cadastre o psicologo responsavel."), g, ciente, criar);
    }
}
