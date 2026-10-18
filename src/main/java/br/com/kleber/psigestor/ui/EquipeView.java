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
        tabela.getColumns().add(Ui.col("Perfil", Usuario::getPapel, 110));
        tabela.getColumns().add(Ui.col("CRP", Usuario::getCrp, 100));
        tabela.getColumns().add(Ui.col("% retido pela clinica", u -> u.isPsicologo()
                ? String.format("%.1f%%", u.getPercentualRepasseClinica()) : "-", 150));
        tabela.getColumns().add(Ui.col("Situacao", u -> u.isAtivo() ? "Ativo" : "Inativo", 90));

        boolean psicologo = ctx.getSessao().usuario().isPsicologo();
        Button novo = new Button("+ Novo usuario");
        novo.getStyleClass().add("primario");
        novo.setDisable(!psicologo);
        novo.setOnAction(e -> novoUsuario());
        Button repasse = new Button("Alterar % de repasse");
        repasse.setDisable(!psicologo);
        repasse.setOnAction(e -> Ui.selecionado(tabela).ifPresent(u -> {
            TextInputDialog d = new TextInputDialog(String.valueOf(u.getPercentualRepasseClinica()));
            d.setHeaderText("Percentual retido pela clinica sobre as sessoes de " + u.getNome());
            d.showAndWait().ifPresent(t -> Ui.executar(() ->
                    ctx.cadastros.atualizarRepasse(u.getId(), Validadores.numeroBR(t).doubleValue())));
            carregar();
        }));
        // revoga o acesso de quem saiu da clinica; o cadastro fica (historico, prontuario e log)
        Button desativar = new Button("Desativar usuario");
        desativar.setDisable(!psicologo);
        desativar.setOnAction(e -> Ui.selecionado(tabela).ifPresent(u -> {
            if (confirmarDesativacao(u)) Ui.executar(() -> ctx.autenticacao.desativar(u.getId()));
            carregar();
        }));
        Button reativar = new Button("Reativar usuario");
        reativar.setDisable(!psicologo);
        reativar.setOnAction(e -> Ui.selecionado(tabela).ifPresent(u -> {
            if (Ui.confirmar("Reativar " + u.getNome() + "? O usuario volta a entrar com a senha que ja tinha."))
                Ui.executar(() -> ctx.autenticacao.reativar(u.getId()));
            carregar();
        }));
        carregar();
        return Ui.tela(Ui.titulo("Equipe & Regras de repasse"), tabela, Ui.linha(novo, repasse, desativar, reativar),
                Ui.aviso("Cada psicologo tem login proprio e so le os proprios registros de prontuario. "
                        + "A secretaria agenda e cuida do financeiro, mas nao acessa o prontuario. "
                        + "Contratos de repasse/sublocacao de sala devem estar formalizados por escrito."));
    }

    /**
     * Psicologo desativado leva junto a unica chave que abre os registros dele: so prossegue se o
     * login for digitado. Secretaria nao tem prontuario, basta a confirmacao simples.
     */
    private boolean confirmarDesativacao(Usuario u) {
        if (!u.isPsicologo())
            return Ui.confirmar("Desativar " + u.getNome() + "? O usuario nao conseguira mais entrar no sistema.");
        TextInputDialog d = new TextInputDialog();
        d.setTitle("Desativar psicologo");
        d.setHeaderText("Desativar " + u.getNome() + "?\n"
                + "Os registros de prontuario dele ficam INACESSIVEIS enquanto estiver desativado:\n"
                + "so a senha dele abre a chave desses registros, e ninguem mais consegue le-los.\n"
                + "Para continuar, digite o login: " + u.getLogin());
        String digitado = d.showAndWait().map(String::strip).orElse(null);
        if (digitado == null) return false;
        if (!digitado.equals(u.getLogin())) {
            Ui.erro("O login digitado nao confere. Nada foi alterado.");
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
        TextField crp = new TextField();
        TextField perc = new TextField("0");
        GridPane g = Ui.formulario();
        g.addRow(0, new Label("Nome"), nome);
        g.addRow(1, new Label("Login"), login);
        g.addRow(2, new Label("Senha inicial"), senha);
        g.addRow(3, new Label("Perfil"), papel);
        g.addRow(4, new Label("CRP (psicologo)"), crp);
        g.addRow(5, new Label("% retido pela clinica"), perc);
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Novo usuario");
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
