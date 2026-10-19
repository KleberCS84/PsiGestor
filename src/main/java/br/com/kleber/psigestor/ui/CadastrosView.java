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
        servicos.getColumns().add(Ui.col("Serviço", Servico::getNome, 260));
        servicos.getColumns().add(Ui.col("Código TUSS", Servico::getCodigoTuss, 120));
        servicos.getColumns().add(Ui.col("Duração", s -> s.getDuracaoMin() + " min", 90));
        servicos.getColumns().add(Ui.col("Valor particular", s -> Ui.moeda(s.getValorCentavos()), 130));
        servicos.setPrefHeight(200);

        TextField sNome = new TextField();
        sNome.setPromptText("Nome (ex.: Psicoterapia individual)");
        TextField sTuss = new TextField();
        sTuss.setPromptText("Cód. TUSS (opcional)");
        TextField sDur = new TextField("50");
        sDur.setPrefWidth(60);
        TextField sValor = new TextField();
        sValor.setPromptText("Valor");
        sValor.setPrefWidth(90);
        // valores definem o que e cobrado: so o psicologo altera (regra garantida no CadastroService)
        boolean psicologo = ctx.getSessao().usuario().isPsicologo();
        Button addServ = new Button("Adicionar serviço");
        addServ.getStyleClass().add("primario");
        addServ.setDisable(!psicologo);
        addServ.setOnAction(e -> {
            if (Ui.executar(() -> {
                ctx.cadastros.salvarServico(new Servico(sNome.getText().trim(), vazioParaNull(sTuss.getText()),
                        inteiro(sDur.getText()), Validadores.centavos(sValor.getText())));
            })) { sNome.clear(); sTuss.clear(); sValor.clear(); carregar(); }
        });

        convenios.getColumns().add(Ui.col("Convênio", Convenio::getNome, 240));
        convenios.getColumns().add(Ui.col("Registro ANS", Convenio::getRegistroAns, 120));
        convenios.getColumns().add(Ui.col("Valor por sessão", c -> Ui.moeda(c.getValorSessaoCentavos()), 130));
        convenios.getColumns().add(Ui.col("Prazo pagto.", c -> c.getPrazoPagamentoDias() + " dias", 110));
        convenios.setPrefHeight(200);

        TextField cNome = new TextField();
        cNome.setPromptText("Nome do convênio");
        TextField cAns = new TextField();
        cAns.setPromptText("Registro ANS");
        TextField cValor = new TextField();
        cValor.setPromptText("Valor sessão");
        cValor.setPrefWidth(100);
        TextField cPrazo = new TextField("30");
        cPrazo.setPrefWidth(60);
        Button addConv = new Button("Adicionar convênio");
        addConv.getStyleClass().add("primario");
        addConv.setDisable(!psicologo);
        addConv.setOnAction(e -> {
            if (Ui.executar(() -> {
                ctx.cadastros.salvarConvenio(new Convenio(cNome.getText().trim(), vazioParaNull(cAns.getText()),
                        Validadores.centavos(cValor.getText()), inteiro(cPrazo.getText())));
            })) { cNome.clear(); cAns.clear(); cValor.clear(); carregar(); }
        });

        carregar();
        return Ui.tela(Ui.titulo("Serviços"), servicos,
                Ui.linha(sNome, sTuss, new Label("min"), sDur, sValor, addServ),
                Ui.aviso("Código TUSS: copie o código do procedimento da tabela TUSS publicada pela ANS ou do "
                        + "contrato com a operadora. O sistema não embute tabelas de terceiros (algumas, como a "
                        + "CBHPM, têm direitos autorais)."),
                Ui.titulo("Convênios"), convenios,
                Ui.linha(cNome, cAns, cValor, new Label("prazo (dias)"), cPrazo, addConv),
                Ui.aviso("Faturamento eletrônico no padrão TISS (XML para operadoras) fica fora do MVP: "
                        + "exige seguir a versão vigente do padrão da ANS e homologação com cada operadora."));
    }

    private void carregar() {
        Ui.executar(() -> {
            servicos.getItems().setAll(ctx.cadastros.listarServicos());
            convenios.getItems().setAll(ctx.cadastros.listarConvenios());
        });
    }

    static int inteiro(String s) {
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { throw new RegraNegocioException("Número inválido: " + s); }
    }

    private static String vazioParaNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
