package br.com.kleber.psigestor.util;

import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class CriptografiaTest {

    @Test
    void cifrarEDecifrarRecuperaOTextoOriginal() {
        SecretKey k = Criptografia.novaChaveDados();
        String texto = "Paciente relata ansiedade; acentuacao: coracao, ninguem.";
        byte[][] r = Criptografia.cifrar(texto, k);
        assertEquals(texto, Criptografia.decifrar(r[1], r[0], k));
    }

    @Test
    void mesmoTextoGeraCifrasDiferentesPorCausaDoIv() {
        SecretKey k = Criptografia.novaChaveDados();
        byte[][] a = Criptografia.cifrar("igual", k);
        byte[][] b = Criptografia.cifrar("igual", k);
        assertFalse(Arrays.equals(a[0], b[0]), "IV deve ser novo a cada cifragem");
        assertFalse(Arrays.equals(a[1], b[1]));
    }

    @Test
    void ivTem12BytesETextoCifradoNaoContemOTextoPuro() {
        SecretKey k = Criptografia.novaChaveDados();
        byte[][] r = Criptografia.cifrar("segredo clinico", k);
        assertEquals(12, r[0].length);
        assertFalse(new String(r[1], StandardCharsets.ISO_8859_1).contains("segredo"));
    }

    @Test
    void chaveErradaFalhaAoDecifrar() {
        byte[][] r = Criptografia.cifrar("x", Criptografia.novaChaveDados());
        assertThrows(RegraNegocioException.class,
                () -> Criptografia.decifrar(r[1], r[0], Criptografia.novaChaveDados()));
    }

    @Test
    void dadoAdulteradoEhDetectado() {
        SecretKey k = Criptografia.novaChaveDados();
        byte[][] r = Criptografia.cifrar("conteudo", k);
        r[1][0] ^= 1;
        assertThrows(RegraNegocioException.class, () -> Criptografia.decifrar(r[1], r[0], k));
    }

    @Test
    void textoVazioFazRoundTrip() {
        SecretKey k = Criptografia.novaChaveDados();
        byte[][] r = Criptografia.cifrar("", k);
        assertEquals("", Criptografia.decifrar(r[1], r[0], k));
    }

    @Test
    void senhaConfereSomenteComASenhaCorreta() {
        String salt = Criptografia.novoSalt();
        String hash = Criptografia.hashSenha("senhaForte1".toCharArray(), salt);
        assertTrue(Criptografia.senhaConfere("senhaForte1".toCharArray(), salt, hash));
        assertFalse(Criptografia.senhaConfere("senhaForte2".toCharArray(), salt, hash));
    }

    @Test
    void saltsDiferentesGeramHashesDiferentes() {
        char[] senha = "mesmaSenha1".toCharArray();
        String s1 = Criptografia.novoSalt();
        String s2 = Criptografia.novoSalt();
        assertNotEquals(s1, s2);
        assertNotEquals(Criptografia.hashSenha(senha, s1), Criptografia.hashSenha(senha, s2));
    }

    @Test
    void hashEhDeterministicoParaMesmoSaltESenha() {
        String salt = Criptografia.novoSalt();
        assertEquals(Criptografia.hashSenha("abc12345".toCharArray(), salt),
                Criptografia.hashSenha("abc12345".toCharArray(), salt));
    }

    @Test
    void envelopeDeChaveRecuperaADekComSenhaCertaEFalhaComErrada() {
        String salt = Criptografia.novoSalt();
        SecretKey dek = Criptografia.novaChaveDados();
        SecretKey kek = Criptografia.derivarChave("senhaForte1".toCharArray(), salt);
        byte[][] emb = Criptografia.embrulharChave(dek, kek);

        SecretKey recuperada = Criptografia.desembrulharChave(emb[1], emb[0], kek);
        assertArrayEquals(dek.getEncoded(), recuperada.getEncoded());

        SecretKey kekErrada = Criptografia.derivarChave("outraSenha1".toCharArray(), salt);
        assertThrows(RegraNegocioException.class,
                () -> Criptografia.desembrulharChave(emb[1], emb[0], kekErrada));
    }

    @Test
    void chavesDeDadosSaoUnicasEDe256Bits() {
        SecretKey a = Criptografia.novaChaveDados();
        SecretKey b = Criptografia.novaChaveDados();
        assertEquals(32, a.getEncoded().length);
        assertFalse(Arrays.equals(a.getEncoded(), b.getEncoded()));
    }
}
