package br.com.kleber.psigestor.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

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

    /** Antes: CadastrosView.numero("150.00") devolvia 15000 e "7.5" devolvia 75. */
    @Test
    void numeroBRAceitaFormatosSemAmbiguidade() {
        assertEquals(0, new BigDecimal("150.00").compareTo(Validadores.numeroBR("150.00")));
        assertEquals(0, new BigDecimal("7.5").compareTo(Validadores.numeroBR("7.5")));
        assertEquals(0, new BigDecimal("1.50").compareTo(Validadores.numeroBR("1.50")));
        assertEquals(0, new BigDecimal("80.50").compareTo(Validadores.numeroBR("80,50")));
        assertEquals(0, new BigDecimal("1234.56").compareTo(Validadores.numeroBR("1.234,56")));
        assertEquals(0, new BigDecimal("1500.5").compareTo(Validadores.numeroBR("1.500,5")));
        assertEquals(0, new BigDecimal("1234567").compareTo(Validadores.numeroBR("1.234.567")));
        assertEquals(0, new BigDecimal("200").compareTo(Validadores.numeroBR(" 200 ")));
        assertEquals(0, BigDecimal.ZERO.compareTo(Validadores.numeroBR("")));
        assertEquals(0, BigDecimal.ZERO.compareTo(Validadores.numeroBR(null)));
    }

    /** Antes: "NaN" passava (e passava na faixa 0..100 do repasse) e "1.234" virava 1234 sem aviso. */
    @Test
    void numeroBRRejeitaFormatosAmbiguosOuInvalidos() {
        for (String s : new String[] { "1.234", "1,234", "1,234.56", "1.23,4", "1,2,3", "NaN", "Infinity",
                "1e9", "-5", "12a", ",5", "5,", "1 000" }) {
            assertThrows(RegraNegocioException.class, () -> Validadores.numeroBR(s), s);
        }
    }

    @Test
    void vazioReconheceNuloEEspacos() {
        assertTrue(Validadores.vazio(null));
        assertTrue(Validadores.vazio(""));
        assertTrue(Validadores.vazio("   "));
        assertFalse(Validadores.vazio(" a "));
    }

    @Test
    void telefoneComDdd() {
        assertTrue(Validadores.telefoneValido("62999990000"));      // celular
        assertTrue(Validadores.telefoneValido("(62) 3333-4444"));   // fixo
        assertTrue(Validadores.telefoneValido("+55 62 99999-0000"));
        assertFalse(Validadores.telefoneValido("99990000"));        // sem DDD
        assertFalse(Validadores.telefoneValido("629999900001"));    // 12 digitos sem 55
        assertFalse(Validadores.telefoneValido("62 9999x0000"));    // letra
        assertFalse(Validadores.telefoneValido(""));
        assertFalse(Validadores.telefoneValido(null));
    }
}
