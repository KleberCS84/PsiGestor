package br.com.kleber.psigestor.ui;

import br.com.kleber.psigestor.model.Papel;
import br.com.kleber.psigestor.service.AutenticacaoService;
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
        boolean[] primeiro = {false};   // banco ilegivel: mostra o login (o erro ja foi exibido)
        Ui.executar(() -> primeiro[0] = ctx.autenticacao.primeiroAcesso());
        VBox caixa = primeiro[0] ? primeiroAcesso() : login();
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
            if (Ui.executar(() -> ctx.autenticacao.entrar(login.getText(), senha.getText().toCharArray()))
                    && aceitouTermos()) {
                oferecerCodigoRecuperacao(senha.getText());
                aoEntrar.run();
            }
            senha.clear();
        });
        Hyperlink esqueci = new Hyperlink("Esqueci a senha");
        esqueci.setOnAction(e -> new RecuperacaoDialog(ctx).mostrar());
        Label marca = new Label("PsiGestor");
        marca.getStyleClass().add("login-marca");
        VBox v = new VBox(12, marca, new Label("Gestao de consultorio com sigilo por padrao"), login, senha, entrar,
                Ui.linha(esqueci, sobre()));
        v.setAlignment(Pos.CENTER_LEFT);
        return v;
    }

    private static Hyperlink sobre() {
        Hyperlink sobre = new Hyperlink("Sobre o PsiGestor");
        sobre.setOnAction(e -> SobreDialog.mostrar());
        return sobre;
    }

    /**
     * Logado sem aceite da versao atual dos termos: so segue se aceitar. Recusa (ou erro ao gravar)
     * encerra a sessao e mantem a tela de login.
     */
    private boolean aceitouTermos() {
        boolean[] precisa = {true};
        if (Ui.executar(() -> precisa[0] = ctx.autenticacao.precisaAceitarTermos()) && !precisa[0]) return true;
        if (TermosDialog.pedirAceite() && Ui.executar(ctx.autenticacao::aceitarTermos)) return true;
        Ui.executar(ctx::sair);
        Ui.info("E preciso aceitar os termos de uso para usar o PsiGestor.");
        return false;
    }

    /** Psicologo sem codigo de recuperacao: oferece a cada login ate gerar. A senha acabou de ser digitada. */
    private void oferecerCodigoRecuperacao(String senhaDigitada) {
        boolean[] precisa = {false};
        Ui.executar(() -> precisa[0] = ctx.autenticacao.precisaCodigoRecuperacao());
        if (!precisa[0]) return;
        ButtonType gerar = new ButtonType("Gerar agora", ButtonBar.ButtonData.OK_DONE);
        ButtonType depois = new ButtonType("Lembrar no proximo login", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert a = new Alert(Alert.AlertType.WARNING, "Voce ainda nao tem um codigo de recuperacao.\n\n"
                + "Sem ele, se a senha for esquecida, o seu prontuario nao pode ser lido por ninguem. "
                + "Gere o codigo agora e guarde-o fora do computador.", gerar, depois);
        a.setTitle("Codigo de recuperacao");
        a.setHeaderText(null);
        if (a.showAndWait().filter(b -> b == gerar).isEmpty()) return;
        String[] codigo = {null};
        if (Ui.executar(() -> codigo[0] = ctx.autenticacao.gerarCodigoRecuperacao(senhaDigitada.toCharArray())))
            CodigoRecuperacaoDialog.mostrar(codigo[0]);
    }

    private VBox primeiroAcesso() {
        TextField nome = new TextField();
        TextField crp = new TextField();
        crp.setPromptText("ex.: 09/12345");
        TextField login = new TextField();
        PasswordField senha = new PasswordField();
        PasswordField senha2 = new PasswordField();
        CheckBox ciente = new CheckBox("Entendo que o prontuario e cifrado com a minha senha:\n"
                + "se eu perder a senha E o codigo de recuperacao, os registros nao podem ser recuperados.");
        ciente.setWrapText(true);
        ciente.setMaxWidth(390);
        ciente.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        GridPane g = Ui.formulario();
        g.addRow(0, new Label("Nome completo"), nome);
        g.addRow(1, new Label("CRP"), crp);
        g.addRow(2, new Label("Login"), login);
        g.addRow(3, new Label("Senha (min. " + AutenticacaoService.SENHA_MINIMA + ")"), senha);
        g.addRow(4, new Label("Repita a senha"), senha2);
        Button criar = new Button("Criar conta do psicologo responsavel");
        criar.getStyleClass().add("primario");
        criar.setOnAction(e -> {
            if (!senha.getText().equals(senha2.getText())) { Ui.erro("As senhas nao conferem."); return; }
            if (!ciente.isSelected()) { Ui.erro("Marque a ciencia sobre a senha do prontuario."); return; }
            // termos antes de criar a conta: recusou, nada fica gravado
            if (!TermosDialog.pedirAceite()) {
                Ui.info("E preciso aceitar os termos de uso para usar o PsiGestor.");
                return;
            }
            String s = senha.getText();
            boolean ok = Ui.executar(() -> {
                ctx.autenticacao.cadastrar(nome.getText(), login.getText(), s.toCharArray(), Papel.PSICOLOGO, crp.getText(), 0);
                ctx.autenticacao.entrar(login.getText(), s.toCharArray());
                ctx.autenticacao.aceitarTermos();
            });
            if (!ok) {
                Ui.executar(ctx::sair);   // sem aceite gravado nao ha sessao; o proximo login pede os termos
                return;
            }
            String[] codigo = {null};
            if (Ui.executar(() -> codigo[0] = ctx.autenticacao.gerarCodigoRecuperacao(s.toCharArray())))
                CodigoRecuperacaoDialog.mostrar(codigo[0]);
            senha.clear();
            senha2.clear();
            aoEntrar.run();
        });
        Label marca = new Label("Bem-vindo ao PsiGestor");
        marca.getStyleClass().add("login-marca");
        return new VBox(10, marca, new Label("Primeiro acesso: cadastre o psicologo responsavel."), g, ciente, criar,
                sobre());
    }
}
