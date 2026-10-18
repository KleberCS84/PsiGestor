package br.com.kleber.psigestor.util;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Seguranca usando apenas a biblioteca padrao do Java (javax.crypto):
 *  - senha: PBKDF2-HMAC-SHA256 com salt aleatorio (nunca guardamos a senha);
 *  - prontuario: AES-256-GCM com uma CHAVE DE DADOS (DEK) propria de cada psicologo;
 *  - a DEK fica gravada no banco "embrulhada" (cifrada) por uma CHAVE DE EMBRULHO (KEK)
 *    derivada da senha. Isso se chama criptografia de envelope.
 * Por que envelope? Trocar a senha so troca o embrulho da DEK (1 UPDATE em usuario);
 * os registros do prontuario nao precisam ser recifrados - e nem poderiam, porque
 * os triggers do banco proibem UPDATE em evolucao.
 * Consequencia importante: sem a senha do psicologo, o prontuario NAO pode ser lido
 * (nem pela secretaria, nem por quem copiar o arquivo .db).
 */
public final class Criptografia {

    private static final int ITERACOES = 210_000;   // recomendacao OWASP para PBKDF2-SHA256
    private static final int TAM_CHAVE_BITS = 256;
    private static final int TAM_IV = 12;
    private static final int TAM_TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Criptografia() { }

    public static String novoSalt() {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    public static String hashSenha(char[] senha, String saltB64) {
        return Base64.getEncoder().encodeToString(pbkdf2(senha, saltB64));
    }

    /** Comparacao em tempo constante para nao vazar informacao por tempo de resposta. */
    public static boolean senhaConfere(char[] senha, String saltB64, String hashEsperadoB64) {
        byte[] calculado = pbkdf2(senha, saltB64);
        byte[] esperado = Base64.getDecoder().decode(hashEsperadoB64);
        return MessageDigest.isEqual(calculado, esperado);
    }

    public static SecretKey derivarChave(char[] senha, String saltB64) {
        return new SecretKeySpec(pbkdf2(senha, saltB64), "AES");
    }

    /** Chave de dados (DEK) nova e aleatoria - nao depende de senha nenhuma. */
    public static SecretKey novaChaveDados() {
        byte[] k = new byte[TAM_CHAVE_BITS / 8];
        RANDOM.nextBytes(k);
        return new SecretKeySpec(k, "AES");
    }

    /** Cifra a DEK com a KEK. Retorna {iv, chaveCifrada}. */
    public static byte[][] embrulharChave(SecretKey chaveDados, SecretKey chaveEmbrulho) {
        return cifrarBytes(chaveDados.getEncoded(), chaveEmbrulho);
    }

    /** Recupera a DEK. Falha (tag GCM invalida) se a KEK veio de uma senha errada. */
    public static SecretKey desembrulharChave(byte[] chaveCifrada, byte[] iv, SecretKey chaveEmbrulho) {
        return new SecretKeySpec(decifrarBytes(chaveCifrada, iv, chaveEmbrulho), "AES");
    }

    /** Retorna {iv, conteudoCifrado}. */
    public static byte[][] cifrar(String texto, SecretKey chave) {
        return cifrarBytes(texto.getBytes(StandardCharsets.UTF_8), chave);
    }

    public static String decifrar(byte[] cifrado, byte[] iv, SecretKey chave) {
        return new String(decifrarBytes(cifrado, iv, chave), StandardCharsets.UTF_8);
    }

    private static byte[][] cifrarBytes(byte[] dados, SecretKey chave) {
        try {
            byte[] iv = new byte[TAM_IV];   // IV novo a cada cifragem: nunca reutilizar IV no GCM
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, chave, new GCMParameterSpec(TAM_TAG_BITS, iv));
            return new byte[][] { iv, c.doFinal(dados) };
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao cifrar", e);
        }
    }

    private static byte[] decifrarBytes(byte[] cifrado, byte[] iv, SecretKey chave) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, chave, new GCMParameterSpec(TAM_TAG_BITS, iv));
            return c.doFinal(cifrado);
        } catch (GeneralSecurityException e) {
            throw new RegraNegocioException("Nao foi possivel decifrar o registro (chave incorreta ou dado adulterado).");
        }
    }

    private static byte[] pbkdf2(char[] senha, String saltB64) {
        PBEKeySpec spec = new PBEKeySpec(senha, Base64.getDecoder().decode(saltB64), ITERACOES, TAM_CHAVE_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        } finally {
            spec.clearPassword();   // o PBEKeySpec guarda uma copia propria do char[] da senha
        }
    }
}
