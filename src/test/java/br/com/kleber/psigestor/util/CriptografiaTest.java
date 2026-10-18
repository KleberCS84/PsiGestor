package br.com.kleber.psigestor.util;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
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
        String hash = Criptografia.hashSenha("senhaForte1".toCharArray(), salt, Criptografia.ITERACOES_ATUAL);
        assertTrue(Criptografia.senhaConfere("senhaForte1".toCharArray(), salt, hash, Criptografia.ITERACOES_ATUAL));
        assertFalse(Criptografia.senhaConfere("senhaForte2".toCharArray(), salt, hash, Criptografia.ITERACOES_ATUAL));
    }

    @Test
    void saltsDiferentesGeramHashesDiferentes() {
        char[] senha = "mesmaSenha1".toCharArray();
        String s1 = Criptografia.novoSalt();
        String s2 = Criptografia.novoSalt();
        assertNotEquals(s1, s2);
        assertNotEquals(Criptografia.hashSenha(senha, s1, Criptografia.ITERACOES_ATUAL), Criptografia.hashSenha(senha, s2, Criptografia.ITERACOES_ATUAL));
    }

    @Test
    void hashEhDeterministicoParaMesmoSaltESenha() {
        String salt = Criptografia.novoSalt();
        assertEquals(Criptografia.hashSenha("abc12345".toCharArray(), salt, Criptografia.ITERACOES_ATUAL),
                Criptografia.hashSenha("abc12345".toCharArray(), salt, Criptografia.ITERACOES_ATUAL));
    }

    @Test
    void envelopeDeChaveRecuperaADekComSenhaCertaEFalhaComErrada() {
        String salt = Criptografia.novoSalt();
        SecretKey dek = Criptografia.novaChaveDados();
        SecretKey kek = Criptografia.derivarChave("senhaForte1".toCharArray(), salt, Criptografia.ITERACOES_ATUAL);
        byte[][] emb = Criptografia.embrulharChave(dek, kek);

        SecretKey recuperada = Criptografia.desembrulharChave(emb[1], emb[0], kek);
        assertArrayEquals(dek.getEncoded(), recuperada.getEncoded());

        SecretKey kekErrada = Criptografia.derivarChave("outraSenha1".toCharArray(), salt, Criptografia.ITERACOES_ATUAL);
        assertThrows(RegraNegocioException.class,
                () -> Criptografia.desembrulharChave(emb[1], emb[0], kekErrada));
    }

    @Test
    void cifrarComSenhaFazIdaEVoltaEFalhaComSenhaErrada() {
        byte[] dados = "conteudo do banco".getBytes(StandardCharsets.UTF_8);
        byte[] pacote = Criptografia.cifrarComSenha(dados, "senhaDoBackup1".toCharArray());
        assertEquals("PSGB", new String(pacote, 0, 4, StandardCharsets.US_ASCII));
        assertEquals(2, pacote[4]);   // formato v2 (B-10); a v1 continua legivel, ver backupV1AntigoAindaAbre
        assertArrayEquals(dados, Criptografia.decifrarComSenha(pacote, "senhaDoBackup1".toCharArray()));
        assertThrows(RegraNegocioException.class,
                () -> Criptografia.decifrarComSenha(pacote, "senhaDoBackup2".toCharArray()));
    }

    @Test
    void cabecalhoDoBackupAdulteradoEDetectado() {
        byte[] pacote = Criptografia.cifrarComSenha(new byte[] { 1, 2, 3 }, "senhaDoBackup1".toCharArray());
        pacote[10] ^= 1;   // dentro do salt: AAD invalida a tag
        assertThrows(RegraNegocioException.class,
                () -> Criptografia.decifrarComSenha(pacote, "senhaDoBackup1".toCharArray()));
    }

    /**
     * B-10: backup gravado no formato v1 (antes das iteracoes irem para o cabecalho) continua abrindo.
     * O pacote e montado aqui com javax.crypto, sem usar o codigo de producao, como um arquivo antigo.
     */
    @Test
    void backupV1AntigoAindaAbre() throws Exception {
        byte[] dados = "banco de antes da v2".getBytes(StandardCharsets.UTF_8);
        char[] senha = "senhaDoBackup1".toCharArray();
        SecureRandom rnd = new SecureRandom();
        byte[] salt = new byte[16];
        byte[] iv = new byte[12];
        rnd.nextBytes(salt);
        rnd.nextBytes(iv);
        byte[] cabecalho = ByteBuffer.allocate(4 + 1 + 16 + 12).put(new byte[] { 'P', 'S', 'G', 'B' })
                .put((byte) 1).put(salt).put(iv).array();
        byte[] chave = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(new PBEKeySpec(senha, salt, 600_000, 256)).getEncoded();
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(chave, "AES"), new GCMParameterSpec(128, iv));
        c.updateAAD(cabecalho);
        byte[] cifrado = c.doFinal(dados);
        byte[] pacoteV1 = ByteBuffer.allocate(cabecalho.length + cifrado.length).put(cabecalho).put(cifrado).array();

        assertArrayEquals(dados, Criptografia.decifrarComSenha(pacoteV1, senha.clone()));
        assertThrows(RegraNegocioException.class,
                () -> Criptografia.decifrarComSenha(pacoteV1, "senhaDoBackup2".toCharArray()));
    }

    /** B-10: o formato atual (v2) grava as iteracoes, e elas sao autenticadas junto com o cabecalho. */
    @Test
    void backupV2GravaIteracoesAutenticadas() {
        byte[] pacote = Criptografia.cifrarComSenha(new byte[] { 1, 2, 3 }, "senhaDoBackup1".toCharArray());
        assertEquals(2, pacote[4]);
        assertEquals(Criptografia.ITERACOES_ATUAL, ByteBuffer.wrap(pacote, 5, 4).getInt());

        byte[] adulterado = pacote.clone();
        ByteBuffer.wrap(adulterado, 5, 4).putInt(Criptografia.ITERACOES_ATUAL + 1);   // ainda na faixa aceita
        assertThrows(RegraNegocioException.class,
                () -> Criptografia.decifrarComSenha(adulterado, "senhaDoBackup1".toCharArray()));

        byte[] absurdo = pacote.clone();
        ByteBuffer.wrap(absurdo, 5, 4).putInt(Integer.MAX_VALUE);                     // nao tenta derivar
        assertThrows(RegraNegocioException.class,
                () -> Criptografia.decifrarComSenha(absurdo, "senhaDoBackup1".toCharArray()));
    }

    @Test
    void chavesDeDadosSaoUnicasEDe256Bits() {
        SecretKey a = Criptografia.novaChaveDados();
        SecretKey b = Criptografia.novaChaveDados();
        assertEquals(32, a.getEncoded().length);
        assertFalse(Arrays.equals(a.getEncoded(), b.getEncoded()));
    }
}
