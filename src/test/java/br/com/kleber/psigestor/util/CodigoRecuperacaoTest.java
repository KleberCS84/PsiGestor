package br.com.kleber.psigestor.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CodigoRecuperacaoTest {

    private static final String FORMATO = "([0-9A-HJKMNP-TV-Z]{4}-){6}[0-9A-HJKMNP-TV-Z]{4}";

    @Test
    void formatoTemSeteGruposSemCaracteresAmbiguos() {
        for (int i = 0; i < 200; i++) {
            String c = CodigoRecuperacao.gerar();
            assertTrue(c.matches(FORMATO), c);
            // 26 x 5 = 130 bits para 128 aleatorios: os 2 bits mais altos sao sempre zero
            assertTrue(CodigoRecuperacao.ALFABETO.indexOf(c.charAt(0)) < 8, c);
        }
    }

    @Test
    void codigosNaoSeRepetem() {
        Set<String> vistos = new HashSet<>();
        for (int i = 0; i < 1000; i++) assertTrue(vistos.add(CodigoRecuperacao.gerar()));
    }

    @Test
    void normalizarAceitaMinusculasEspacosEConfusoes() {
        String c = CodigoRecuperacao.gerar();
        String esperado = c.replace("-", "");
        String digitado = c.toLowerCase().replace('-', ' ').replace('0', 'o').replace('1', 'l');
        assertEquals(esperado, new String(CodigoRecuperacao.normalizar(digitado)));
        assertEquals(esperado, new String(CodigoRecuperacao.normalizar(" " + esperado + " ")));
    }

    @Test
    void verificadorPegaQualquerTrocaDeUmCaractere() {
        String c = CodigoRecuperacao.gerar().replace("-", "");
        for (int i = 0; i < c.length(); i++) {
            for (char x : CodigoRecuperacao.ALFABETO.toCharArray()) {
                if (x == c.charAt(i)) continue;
                String errado = c.substring(0, i) + x + c.substring(i + 1);
                assertThrows(RegraNegocioException.class, () -> CodigoRecuperacao.normalizar(errado), errado);
            }
        }
    }

    @Test
    void verificadorPegaInversaoDeVizinhos() {
        String c = CodigoRecuperacao.gerar().replace("-", "");
        for (int i = 0; i + 1 < CodigoRecuperacao.TAM_DADOS; i++) {
            if (c.charAt(i) == c.charAt(i + 1)) continue;
            String errado = c.substring(0, i) + c.charAt(i + 1) + c.charAt(i) + c.substring(i + 2);
            assertThrows(RegraNegocioException.class, () -> CodigoRecuperacao.normalizar(errado), errado);
        }
    }

    @Test
    void tamanhoOuCaractereInvalidoERecusadoSemEcoarOTexto() {
        String c = CodigoRecuperacao.gerar();
        for (String errado : new String[] { null, "", c.substring(0, c.length() - 1), c + "0", c.replace('-', 'U'),
                c.substring(0, 5) + "#" + c.substring(6) }) {
            RegraNegocioException e = assertThrows(RegraNegocioException.class,
                    () -> CodigoRecuperacao.normalizar(errado));
            assertEquals(CodigoRecuperacao.DIGITADO_ERRADO, e.getMessage());
        }
    }

    @Test
    void ultimos4() {
        assertEquals("WXYZ", CodigoRecuperacao.ultimos4("0000-0000-0000-0000-0000-0000-WXYZ"));
    }
}
