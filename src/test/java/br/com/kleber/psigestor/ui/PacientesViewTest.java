package br.com.kleber.psigestor.ui;

import br.com.kleber.psigestor.model.Paciente;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/** O formulario edita uma copia: a linha da tabela so muda quando a tabela recarrega do banco. */
class PacientesViewTest {

    @Test
    void rascunhoCopiaTodosOsCampos() {
        Paciente p = completo();
        Paciente c = PacientesView.rascunho(p);
        assertNotSame(p, c);
        assertEquals(7, c.getId());
        assertEquals("Joao Lima", c.getNome());
        assertEquals("52998224725", c.getCpf());
        assertEquals(LocalDate.of(1990, 5, 20), c.getDataNascimento());
        assertEquals("62999990000", c.getTelefone());
        assertEquals("joao@exemplo.com", c.getEmail());
        assertEquals("Maria (mae)", c.getContatoEmergencia());
        assertEquals("Jose", c.getResponsavelLegal());
        assertEquals(3, c.getConvenioId());
        assertEquals("123456", c.getNumeroCarteirinha());
        assertTrue(c.isArquivado());   // editar um arquivado nao pode reativa-lo sem querer
    }

    /** Antes: o filtro do OK alterava o objeto da tabela e a validacao falhava depois. */
    @Test
    void alterarORascunhoNaoAlteraOriginal() {
        Paciente p = completo();
        Paciente c = PacientesView.rascunho(p);
        c.setNome("Digitado e nao salvo");
        c.setTelefone("123");
        c.setConvenioId(null);
        assertEquals("Joao Lima", p.getNome());
        assertEquals("62999990000", p.getTelefone());
        assertEquals(3, p.getConvenioId());
    }

    private static Paciente completo() {
        Paciente p = new Paciente("Joao Lima", LocalDate.of(1990, 5, 20), "62999990000");
        p.setId(7);
        p.setCpf("52998224725");
        p.setEmail("joao@exemplo.com");
        p.setContatoEmergencia("Maria (mae)");
        p.setResponsavelLegal("Jose");
        p.setConvenioId(3);
        p.setNumeroCarteirinha("123456");
        p.setArquivado(true);
        return p;
    }
}
