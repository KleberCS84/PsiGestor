package br.com.kleber.psigestor.ui;

import br.com.kleber.psigestor.model.Convenio;
import br.com.kleber.psigestor.model.Servico;
import br.com.kleber.psigestor.service.Contexto;
import br.com.kleber.psigestor.util.RegraNegocioException;
import br.com.kleber.psigestor.util.Validadores;
import javafx.scene.Node;
import javafx.scene.control.*;

/** Servicos (com codigo TUSS opcional) e Convenios. */
public class CadastrosView {
    private final Contexto ctx;
    private final TableView<Servico> servicos = Ui.tabela();
    private final TableView<Convenio> convenios = Ui.tabela();

    public CadastrosView(Contexto ctx) { this.ctx = ctx; }

    public Node criar() {
        servicos.getColumns().add(Ui.col("Servico", Servico::getNome, 260));
        servicos.getColumns().add(Ui.col("Codigo TUSS", Servico::getCodigoTuss, 120));
        servicos.getColumns().add(Ui.col("Duracao", s -> s.getDuracaoMin() + " min", 90));
        servicos.getColumns().add(Ui.col("Valor particular", s -> Ui.moeda(s.getValor()), 130));
        servicos.setPrefHeight(200);

        TextField sNome = new TextField();
        sNome.setPromptText("Nome (ex.: Psicoterapia individual)");
        TextField sTuss = new TextField();
        sTuss.setPromptText("Cod. TUSS (opcional)");
        TextField sDur = new TextField("50");
        sDur.setPrefWidth(60);
        TextField sValor = new TextField();
        sValor.setPromptText("Valor");
        sValor.setPrefWidth(90);
        // valores definem o que e cobrado: so o psicologo altera (regra garantida no CadastroService)
        boolean psicologo = ctx.getSessao().usuario().isPsicologo();
        Button addServ = new Button("Adicionar servico");
        addServ.getStyleClass().add("primario");
        addServ.setDisable(!psicologo);
        addServ.setOnAction(e -> {
            if (Ui.executar(() -> {
                ctx.cadastros.salvarServico(new Servico(sNome.getText().trim(), vazioParaNull(sTuss.getText()),
                        inteiro(sDur.getText()), Validadores.numeroBR(sValor.getText()).doubleValue()));
            })) { sNome.clear(); sTuss.clear(); sValor.clear(); carregar(); }
        });

        convenios.getColumns().add(Ui.col("Convenio", Convenio::getNome, 240));
        convenios.getColumns().add(Ui.col("Registro ANS", Convenio::getRegistroAns, 120));
        convenios.getColumns().add(Ui.col("Valor por sessao", c -> Ui.moeda(c.getValorSessao()), 130));
        convenios.getColumns().add(Ui.col("Prazo pagto.", c -> c.getPrazoPagamentoDias() + " dias", 110));
        convenios.setPrefHeight(200);

        TextField cNome = new TextField();
        cNome.setPromptText("Nome do convenio");
        TextField cAns = new TextField();
        cAns.setPromptText("Registro ANS");
        TextField cValor = new TextField();
        cValor.setPromptText("Valor sessao");
        cValor.setPrefWidth(100);
        TextField cPrazo = new TextField("30");
        cPrazo.setPrefWidth(60);
        Button addConv = new Button("Adicionar convenio");
        addConv.getStyleClass().add("primario");
        addConv.setDisable(!psicologo);
        addConv.setOnAction(e -> {
            if (Ui.executar(() -> {
                ctx.cadastros.salvarConvenio(new Convenio(cNome.getText().trim(), vazioParaNull(cAns.getText()),
                        Validadores.numeroBR(cValor.getText()).doubleValue(), inteiro(cPrazo.getText())));
            })) { cNome.clear(); cAns.clear(); cValor.clear(); carregar(); }
        });

        carregar();
        return Ui.tela(Ui.titulo("Servicos"), servicos,
                Ui.linha(sNome, sTuss, new Label("min"), sDur, sValor, addServ),
                Ui.aviso("Codigo TUSS: copie o codigo do procedimento da tabela TUSS publicada pela ANS ou do "
                        + "contrato com a operadora. O sistema nao embute tabelas de terceiros (algumas, como a "
                        + "CBHPM, tem direitos autorais)."),
                Ui.titulo("Convenios"), convenios,
                Ui.linha(cNome, cAns, cValor, new Label("prazo (dias)"), cPrazo, addConv),
                Ui.aviso("Faturamento eletronico no padrao TISS (XML para operadoras) fica fora do MVP: "
                        + "exige seguir a versao vigente do padrao da ANS e homologacao com cada operadora."));
    }

    private void carregar() {
        servicos.getItems().setAll(ctx.cadastros.listarServicos());
        convenios.getItems().setAll(ctx.cadastros.listarConvenios());
    }

    static int inteiro(String s) {
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { throw new RegraNegocioException("Numero invalido: " + s); }
    }

    private static String vazioParaNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
