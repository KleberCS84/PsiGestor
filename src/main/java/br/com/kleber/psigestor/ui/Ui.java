package br.com.kleber.psigestor.ui;

import br.com.kleber.psigestor.dao.DaoException;
import br.com.kleber.psigestor.util.LogTecnico;
import br.com.kleber.psigestor.util.RegraNegocioException;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/** Utilitarios de interface reaproveitados por todas as telas. */
public final class Ui {
    public static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    public static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");
    public static final DateTimeFormatter DATA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final NumberFormat MOEDA = NumberFormat.getCurrencyInstance(Locale.of("pt", "BR"));

    private Ui() { }

    /** Dinheiro trafega em centavos (long); so aqui vira texto "R$ 1.234,56". */
    public static String moeda(long centavos) { return MOEDA.format(BigDecimal.valueOf(centavos, 2)); }

    /** Coluna de tabela a partir de uma funcao (evita PropertyValueFactory e reflexao). */
    public static <T> TableColumn<T, String> col(String titulo, Function<T, Object> f, double largura) {
        TableColumn<T, String> c = new TableColumn<>(titulo);
        c.setCellValueFactory(d -> {
            Object v = f.apply(d.getValue());
            return new SimpleStringProperty(v == null ? "" : v.toString());
        });
        c.setPrefWidth(largura);
        return c;
    }

    /** ComboBox mostra o rotulo; o valor selecionado continua sendo o proprio item (ex.: o enum). */
    public static <T> void rotular(ComboBox<T> combo, Function<T, String> rotulo) {
        combo.setConverter(new StringConverter<>() {
            @Override public String toString(T item) { return item == null ? "" : rotulo.apply(item); }
            @Override public T fromString(String texto) {
                return combo.getItems().stream().filter(i -> rotulo.apply(i).equals(texto)).findFirst().orElse(null);
            }
        });
    }

    /** Formas de pagamento sao gravadas sem acento (AgendaService.FORMAS_PAGAMENTO); aqui so o texto de tela. */
    private static final Map<String, String> FORMA_PAGAMENTO_TELA = Map.of(
            "Cartao de debito", "Cartão de débito",
            "Cartao de credito", "Cartão de crédito",
            "Transferencia", "Transferência",
            "Convenio", "Convênio");

    public static String formaPagamento(String gravada) {
        return gravada == null ? null : FORMA_PAGAMENTO_TELA.getOrDefault(gravada, gravada);
    }

    public static <T> TableView<T> tabela() {
        TableView<T> t = new TableView<>();
        t.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        t.setPlaceholder(new Label("Nada para mostrar."));
        VBox.setVgrow(t, Priority.ALWAYS);
        return t;
    }

    public static VBox card(String titulo, String valor, String classeCor) {
        Label v = new Label(valor);
        v.getStyleClass().add("card-valor");
        Label t = new Label(titulo);
        t.getStyleClass().add("card-titulo");
        VBox box = new VBox(4, v, t);
        box.getStyleClass().addAll("card", classeCor);
        box.setPrefWidth(230);
        return box;
    }

    public static Label titulo(String texto) {
        Label l = new Label(texto);
        l.getStyleClass().add("titulo-tela");
        return l;
    }

    public static Label aviso(String texto) {
        Label l = new Label(texto);
        l.setWrapText(true);
        l.getStyleClass().add("aviso");
        l.setMaxWidth(Double.MAX_VALUE);
        l.setMinHeight(Region.USE_PREF_SIZE); // quebra linha em vez de cortar com "..."
        return l;
    }

    public static VBox tela(javafx.scene.Node... filhos) {
        VBox v = new VBox(14, filhos);
        v.setPadding(new Insets(22));
        return v;
    }

    public static HBox linha(javafx.scene.Node... filhos) {
        HBox h = new HBox(10, filhos);
        h.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return h;
    }

    public static Region espaco() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    public static GridPane formulario() {
        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(8);
        g.setPadding(new Insets(10));
        return g;
    }

    public static final String ERRO_GENERICO = "Não foi possível concluir a operação. "
            + "Os detalhes técnicos foram registrados no log do sistema.";

    /** Como o erro aparece na tela; os testes trocam por um coletor (sem abrir janela). */
    static Consumer<String> exibirErro = Ui::erro;

    /** Executa uma acao mostrando erros de regra de negocio em uma janela amigavel. */
    public static boolean executar(Runnable acao) {
        try {
            acao.run();
            return true;
        } catch (RuntimeException e) {
            exibirErro.accept(mensagemPara(e));
        }
        return false;
    }

    /**
     * Regra de negocio: a mensagem e para o usuario. Qualquer outra (banco, bug): mensagem do SQLite ou
     * interna nao vai para a tela; a pilha vai para o log tecnico (sem dados de paciente).
     */
    static String mensagemPara(RuntimeException e) {
        if (e instanceof RegraNegocioException) return e.getMessage();
        LogTecnico.erro(e instanceof DaoException ? "Erro de banco de dados" : "Erro inesperado", e);
        return ERRO_GENERICO;
    }

    public static void erro(String msg) {
        Alert a = new Alert(Alert.AlertType.WARNING, msg, ButtonType.OK);
        a.setHeaderText(null);
        a.setTitle("Atenção");
        a.showAndWait();
    }

    public static void info(String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK);
        a.setHeaderText(null);
        a.setTitle("PsiGestor");
        a.showAndWait();
    }

    public static boolean confirmar(String msg) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, msg, ButtonType.YES, ButtonType.NO);
        a.setHeaderText(null);
        Optional<ButtonType> r = a.showAndWait();
        return r.isPresent() && r.get() == ButtonType.YES;
    }

    public static <T> Optional<T> selecionado(TableView<T> t) {
        T item = t.getSelectionModel().getSelectedItem();
        if (item == null) erro("Selecione um item na tabela.");
        return Optional.ofNullable(item);
    }
}
