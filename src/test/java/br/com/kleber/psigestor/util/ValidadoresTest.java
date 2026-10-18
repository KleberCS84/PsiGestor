package br.com.kleber.psigestor.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ValidadoresTest {

    @Test
    void cpfValidoComESemMascara() {
        assertTrue(Validadores.cpfValido("529.982.247-25"));
        assertTrue(Validadores.cpfValido("52998224725"));
    }

    @Test
    void cpfComDigitoVerificadorErradoEInvalido() {
        assertFalse(Validadores.cpfValido("529.982.247-24"));
        assertFalse(Validadores.cpfValido("529.982.247-35"));
    }

    @Test
    void cpfComTodosDigitosIguaisEInvalido() {
        assertFalse(Validadores.cpfValido("111.111.111-11"));
        assertFalse(Validadores.cpfValido("00000000000"));
    }

    @Test
    void cpfComTamanhoErradoOuNuloEInvalido() {
        assertFalse(Validadores.cpfValido("123"));
        assertFalse(Validadores.cpfValido("529982247250"));
        assertFalse(Validadores.cpfValido(null));
        assertFalse(Validadores.cpfValido(""));
    }

    @Test
    void somenteDigitosRemoveFormatacao() {
        assertEquals("12345678900", Validadores.somenteDigitos("123.456.789-00"));
        assertEquals("", Validadores.somenteDigitos(null));
        assertEquals("", Validadores.somenteDigitos("abc"));
    }

    @Test
    void vazioReconheceNuloEEspacos() {
        assertTrue(Validadores.vazio(null));
        assertTrue(Validadores.vazio(""));
        assertTrue(Validadores.vazio("   "));
        assertFalse(Validadores.vazio(" a "));
    }
}
