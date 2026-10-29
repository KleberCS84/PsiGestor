package br.com.kleber.psigestor.ui;

import br.com.kleber.psigestor.model.Papel;
import br.com.kleber.psigestor.model.Usuario;
import br.com.kleber.psigestor.service.Contexto;
import br.com.kleber.psigestor.util.Validadores;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;

public class EquipeView {
    private final Contexto ctx;
    private final TableView<Usuario> tabela = Ui.tabela();

    public EquipeView(Contexto ctx) { this.ctx = ctx; }

    public Node criar() {
        tabela.getColumns().add(Ui.col("Nome", Usuario::getNome, 220));
        tabela.getColumns().add(Ui.col("Login", Usuario::getLogin, 120));
        tabela.getColumns().add(Ui.col("Perfil", u -> u.getPapel().rotulo(), 110));
        tabela.getColumns().add(Ui.col("CRP", Usuario::getCrp, 100));
        tabela.getColumns().add(Ui.col("% retido pela clínica", u -> u.isPsicologo()
                ? String.format("%.1f%%", u.getPercentualRepasseClinica()) : "-", 150));
        tabela.getColumns().add(Ui.col("Situação", u -> u.isAtivo() ? "Ativo" : "Inativo", 90));

        boolean psicologo = ctx.usuarioLogado().isPsicologo();
        Button novo = new Button("+ Novo usuário");
        novo.getStyleClass().add("primario");
        novo.setDisable(!psicologo);
        novo.setOnAction(e -> novoUsuario());
        Button repasse = new Button("Alterar % de repasse");
        repasse.setDisable(!psicologo);
        repasse.setOnAction(e -> Ui.selecionado(tabela).ifPresent(u -> {
            TextInputDialog d = new TextInputDialog(String.valueOf(u.getPercentualRepasseClinica()));
            d.setHeaderText("Percentual retido pela clínica sobre as sessões de " + u.getNome());
            d.showAndWait().ifPresent(t -> Ui.executar(() ->
                    ctx.cadastros.atualizarRepasse(u.getId(), Validadores.numeroBR(t).doubleValue())));
            carregar();
        }));
        // revoga o acesso de quem saiu da clinica; o cadastro fica (historico, prontuario e log)
        Button desativar = new Button("Desativar usuário");
        desativar.setDisable(!psicologo);
        desativar.setOnAction(e -> Ui.selecionado(tabela).ifPresent(u -> {
            if (confirmarDesativacao(u)) Ui.executar(() -> ctx.autenticacao.desativar(u.getId()));
            carregar();
        }));
        Button reativar = new Button("Reativar usuário");
        reativar.setDisable(!psicologo);
        reativar.setOnAction(e -> Ui.selecionado(tabela).ifPresent(u -> {
            if (Ui.confirmar("Reativar " + u.getNome() + "? O usuário volta a entrar com a senha que já tinha."))
                Ui.executar(() -> ctx.autenticacao.reativar(u.getId()));
            carregar();
        }));
        // secretaria nao tem chave de prontuario: a senha dela pode ser definida por um psicologo
        Button redefinir = new Button("Redefinir senha (secretário/a)");
        redefinir.setDisable(!psicologo);
        redefinir.setOnAction(e -> Ui.selecionado(tabela).ifPresent(this::redefinirSenha));
        carregar();
        return Ui.tela(Ui.titulo("Equipe & Regras de repasse"), tabela,
                Ui.linha(novo, repasse, desativar, reativar, redefinir),
                Ui.aviso("Cada psicólogo tem login próprio e só lê os próprios registros de prontuário. "
                        + "A secretária agenda e cuida do financeiro, mas não acessa o prontuário. "
                        + "Contratos de repasse/sublocação de sala devem estar formalizados por escrito."));
    }

    /**
     * Psicologo desativado leva junto a unica chave que abre os registros dele: so prossegue se o
     * login for digitado. Secretaria nao tem prontuario, basta a confirmacao simples.
     */
    private void redefinirSenha(Usuario u) {
        if (u.isPsicologo()) {
            Ui.erro("Psicólogo redefine a própria senha com o código de recuperação (\"Esqueci a senha\" no login).");
            return;
        }
        PasswordField nova = new PasswordField();
        PasswordField repetir = new PasswordField();
        GridPane g = Ui.formulario();
        g.addRow(0, new Label("Nova senha (min. "
                + br.com.kleber.psigestor.service.AutenticacaoService.SENHA_MINIMA + ")"), nova);
        g.addRow(1, new Label("Repita a nova senha"), repetir);
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Redefinir senha de " + u.getNome());
        d.setHeaderText(null);
        d.getDialogPane().setContent(g);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(ActionEvent.ACTION, ev -> {
            if (!nova.getText().equals(repetir.getText())) {
                Ui.erro("As senhas não conferem.");
                ev.consume();
                return;
            }
            if (!Ui.executar(() -> ctx.autenticacao.redefinirSenhaSecretaria(u.getId(), nova.getText().toCharArray())))
                ev.consume();
        });
        if (d.showAndWait().filter(b -> b == ButtonType.OK).isPresent())
            Ui.info("Senha redefinida. Entregue a nova senha pessoalmente.");
        nova.clear();
        repetir.clear();
    }

    private boolean confirmarDesativacao(Usuario u) {
        if (!u.isPsicologo())
            return Ui.confirmar("Desativar " + u.getNome() + "? O usuário não conseguirá mais entrar no sistema.");
        TextInputDialog d = new TextInputDialog();
        d.setTitle("Desativar psicólogo");
        d.setHeaderText("Desativar " + u.getNome() + "?\n"
                + "Os registros de prontuário dele ficam INACESSÍVEIS enquanto estiver desativado:\n"
                + "só a senha dele abre a chave desses registros, e ninguém mais consegue lê-los.\n"
                + "Para continuar, digite o login: " + u.getLogin());
        String digitado = d.showAndWait().map(String::strip).orElse(null);
        if (digitado == null) return false;
        if (!digitado.equals(u.getLogin())) {
            Ui.erro("O login digitado não confere. Nada foi alterado.");
            return false;
        }
        return true;
    }

    private void carregar() { Ui.executar(() -> tabela.getItems().setAll(ctx.cadastros.listarUsuarios())); }

    private void novoUsuario() {
        TextField nome = new TextField();
        TextField login = new TextField();
        PasswordField senha = new PasswordField();
        ComboBox<Papel> papel = new ComboBox<>();
        papel.getItems().setAll(Papel.values());
        papel.setValue(Papel.SECRETARIA);
        Ui.rotular(papel, Papel::rotulo);
        TextField crp = new TextField();
        TextField perc = new TextField("0");
        GridPane g = Ui.formulario();
        g.addRow(0, new Label("Nome"), nome);
        g.addRow(1, new Label("Login"), login);
        g.addRow(2, new Label("Senha inicial"), senha);
        g.addRow(3, new Label("Perfil"), papel);
        g.addRow(4, new Label("CRP (psicólogo)"), crp);
        g.addRow(5, new Label("% retido pela clínica"), perc);
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Novo usuário");
        d.getDialogPane().setContent(g);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(ActionEvent.ACTION, ev -> {
            if (!Ui.executar(() -> ctx.autenticacao.cadastrar(nome.getText(), login.getText(),
                    senha.getText().toCharArray(), papel.getValue(), crp.getText(), Validadores.numeroBR(perc.getText()).doubleValue())))
                ev.consume();
        });
        d.showAndWait();
        carregar();
    }
}
