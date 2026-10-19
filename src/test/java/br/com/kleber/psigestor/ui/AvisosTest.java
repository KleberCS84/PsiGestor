package br.com.kleber.psigestor.ui;

import br.com.kleber.psigestor.model.Modalidade;
import br.com.kleber.psigestor.model.Papel;
import br.com.kleber.psigestor.model.StatusAtendimento;
import br.com.kleber.psigestor.service.AgendaService;
import br.com.kleber.psigestor.util.Database;
import br.com.kleber.psigestor.util.Versao;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Textos de aviso que o usuario precisa ver (testados sem abrir janela JavaFX). */
class AvisosTest {

    /** M-7: a restauracao avisa de tudo que volta ao estado da data do backup. */
    @Test
    void avisoDeRestauracaoCitaSenhasUsuariosEAnonimizacoes() {
        String aviso = PrivacidadeView.AVISO_RESTAURACAO;
        assertTrue(aviso.contains("senhas"));
        assertTrue(aviso.contains("desativados"));
        assertTrue(aviso.contains("ATIVOS"));
        assertTrue(aviso.contains("anonimizados"));
    }

    /** Tela Sobre: a versao vem do pom.xml (versao.properties filtrado pelo Maven), nao do literal. */
    @Test
    void versaoVemDoPom() {
        String v = Versao.atual();
        assertNotEquals(Versao.DESCONHECIDA, v);
        assertFalse(v.contains("${"), v);
        assertTrue(v.matches("\\d+\\.\\d+\\.\\d+.*"), v);   // jpackage exige versao numerica
    }

    /** M-6: a falha na partida diz onde esta a copia de antes da migracao e onde esta o detalhe tecnico. */
    @Test
    void mensagemDeFalhaNaPartidaApontaCopiaELogs() {
        Path pasta = Path.of("C:", "Users", "fulano", "PsiGestor");
        String msg = PsiGestorApp.mensagemFalhaAoAbrir(pasta);
        assertTrue(msg.contains(pasta.resolve("psigestor.db.pre-v" + Database.VERSAO_ESQUEMA).toString()), msg);
        assertTrue(msg.contains(pasta.resolve("logs").toString()), msg);
        assertTrue(msg.contains("Nenhum dado foi apagado"), msg);
        assertTrue(msg.contains("versão mais antiga"), msg);
    }

    /** Enum na tela usa o rotulo com acento; o name() (gravado no banco) nao muda. */
    @Test
    void enumsNaTelaTemRotuloComAcentoSemMudarONome() {
        assertEquals("Psicólogo(a)", Papel.PSICOLOGO.rotulo());
        assertEquals("Secretário(a)", Papel.SECRETARIA.rotulo());
        assertEquals("On-line", Modalidade.ONLINE.rotulo());
        assertEquals("Realizado", StatusAtendimento.REALIZADO.rotulo());
        assertEquals("PSICOLOGO", Papel.PSICOLOGO.name());
        assertEquals("ONLINE", Modalidade.ONLINE.name());
        for (StatusAtendimento s : StatusAtendimento.values()) assertFalse(s.rotulo().isBlank(), s.name());
    }

    /** Forma de pagamento: gravada sem acento (validada pelo service), exibida com acento. */
    @Test
    void formaDePagamentoGravadaSemAcentoApareceComAcento() {
        assertEquals("Cartão de débito", Ui.formaPagamento("Cartao de debito"));
        assertEquals("Cartão de crédito", Ui.formaPagamento("Cartao de credito"));
        assertEquals("Transferência", Ui.formaPagamento("Transferencia"));
        assertEquals("Convênio", Ui.formaPagamento("Convenio"));
        assertEquals("PIX", Ui.formaPagamento("PIX"));
        for (String gravada : AgendaService.FORMAS_PAGAMENTO) assertNotNull(Ui.formaPagamento(gravada));
        assertTrue(AgendaService.FORMAS_PAGAMENTO.contains("Cartao de debito"));   // valor do banco intacto
    }
}
