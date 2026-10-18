package br.com.kleber.psigestor.util;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
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

    /**
     * Iteracoes do PBKDF2-SHA256 para senhas novas ou trocadas (OWASP: ~600.000 para SHA-256).
     * A contagem fica gravada em usuario.iteracoes: quem tem menos e migrado no proximo login.
     */
    public static final int ITERACOES_ATUAL = 600_000;
    /** Contagem usada antes da v4 do esquema (210.000 e o valor do OWASP para SHA-512, nao SHA-256). */
    public static final int ITERACOES_LEGADO = 210_000;
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

    public static String hashSenha(char[] senha, String saltB64, int iteracoes) {
        return Base64.getEncoder().encodeToString(pbkdf2(senha, saltB64, iteracoes));
    }

    /** Comparacao em tempo constante para nao vazar informacao por tempo de resposta. */
    public static boolean senhaConfere(char[] senha, String saltB64, String hashEsperadoB64, int iteracoes) {
        byte[] calculado = pbkdf2(senha, saltB64, iteracoes);
        byte[] esperado = Base64.getDecoder().decode(hashEsperadoB64);
        return MessageDigest.isEqual(calculado, esperado);
    }

    public static SecretKey derivarChave(char[] senha, String saltB64, int iteracoes) {
        return new SecretKeySpec(pbkdf2(senha, saltB64, iteracoes), "AES");
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

    /**
     * Formato do arquivo de backup:
     *  v1 (antigo, so leitura): "PSGB" + versao (1 byte) + salt (16) + iv (12) + AES-GCM(dados), 600.000 iteracoes fixas;
     *  v2 (atual): "PSGB" + versao (1 byte) + iteracoes (int, 4 bytes) + salt (16) + iv (12) + AES-GCM(dados).
     * Com as iteracoes no proprio arquivo, subir ITERACOES_ATUAL nao impede abrir backups antigos.
     */
    private static final byte[] MAGICO_BACKUP = { 'P', 'S', 'G', 'B' };
    private static final byte VERSAO_BACKUP_V1 = 1;
    private static final byte VERSAO_BACKUP = 2;
    /** Contagem implicita da v1 (era ITERACOES_ATUAL quando o formato foi criado). */
    static final int ITERACOES_BACKUP_V1 = 600_000;
    /** Fora disso o cabecalho e lixo; o limite superior evita travar a tela num PBKDF2 sem fim. */
    private static final int ITERACOES_BACKUP_MIN = 100_000;
    private static final int ITERACOES_BACKUP_MAX = 10_000_000;
    private static final int TAM_SALT = 16;
    private static final int TAM_CABECALHO_V1 = MAGICO_BACKUP.length + 1 + TAM_SALT + TAM_IV;
    private static final int TAM_CABECALHO = MAGICO_BACKUP.length + 1 + Integer.BYTES + TAM_SALT + TAM_IV;

    /**
     * Cifra um arquivo inteiro com uma senha (backup): chave PBKDF2 com salt proprio do arquivo.
     * O cabecalho entra como AAD: alterar versao, iteracoes, salt ou IV tambem invalida a tag.
     */
    public static byte[] cifrarComSenha(byte[] dados, char[] senha) {
        byte[] salt = new byte[TAM_SALT];
        byte[] iv = new byte[TAM_IV];
        RANDOM.nextBytes(salt);
        RANDOM.nextBytes(iv);
        byte[] cabecalho = ByteBuffer.allocate(TAM_CABECALHO)
                .put(MAGICO_BACKUP).put(VERSAO_BACKUP).putInt(ITERACOES_ATUAL).put(salt).put(iv)
                .array();
        byte[] chave = pbkdf2(senha, Base64.getEncoder().encodeToString(salt), ITERACOES_ATUAL);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(chave, "AES"), new GCMParameterSpec(TAM_TAG_BITS, iv));
            c.updateAAD(cabecalho);
            byte[] cifrado = c.doFinal(dados);
            byte[] pacote = new byte[TAM_CABECALHO + cifrado.length];
            System.arraycopy(cabecalho, 0, pacote, 0, TAM_CABECALHO);
            System.arraycopy(cifrado, 0, pacote, TAM_CABECALHO, cifrado.length);
            return pacote;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao cifrar", e);
        } finally {
            Arrays.fill(chave, (byte) 0);
        }
    }

    /**
     * Inverso de cifrarComSenha; abre v2 e a v1 antiga. Senha errada e arquivo adulterado dao o
     * mesmo erro (o GCM nao distingue).
     */
    public static byte[] decifrarComSenha(byte[] pacote, char[] senha) {
        RegraNegocioException formatoInvalido =
                new RegraNegocioException("Arquivo nao e um backup do PsiGestor ou versao nao suportada.");
        if (pacote.length <= MAGICO_BACKUP.length
                || !Arrays.equals(pacote, 0, MAGICO_BACKUP.length, MAGICO_BACKUP, 0, MAGICO_BACKUP.length))
            throw formatoInvalido;
        byte versao = pacote[MAGICO_BACKUP.length];
        int tamCabecalho;
        int iteracoes;
        int inicioSalt;
        if (versao == VERSAO_BACKUP_V1) {
            tamCabecalho = TAM_CABECALHO_V1;
            iteracoes = ITERACOES_BACKUP_V1;
            inicioSalt = MAGICO_BACKUP.length + 1;
        } else if (versao == VERSAO_BACKUP) {
            tamCabecalho = TAM_CABECALHO;
            if (pacote.length < tamCabecalho) throw formatoInvalido;
            iteracoes = ByteBuffer.wrap(pacote, MAGICO_BACKUP.length + 1, Integer.BYTES).getInt();
            if (iteracoes < ITERACOES_BACKUP_MIN || iteracoes > ITERACOES_BACKUP_MAX) throw formatoInvalido;
            inicioSalt = MAGICO_BACKUP.length + 1 + Integer.BYTES;
        } else {
            throw formatoInvalido;
        }
        if (pacote.length < tamCabecalho + TAM_TAG_BITS / 8) throw formatoInvalido;
        byte[] salt = Arrays.copyOfRange(pacote, inicioSalt, inicioSalt + TAM_SALT);
        byte[] iv = Arrays.copyOfRange(pacote, inicioSalt + TAM_SALT, tamCabecalho);
        byte[] chave = pbkdf2(senha, Base64.getEncoder().encodeToString(salt), iteracoes);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(chave, "AES"), new GCMParameterSpec(TAM_TAG_BITS, iv));
            c.updateAAD(pacote, 0, tamCabecalho);
            return c.doFinal(pacote, tamCabecalho, pacote.length - tamCabecalho);
        } catch (GeneralSecurityException e) {
            throw new RegraNegocioException("Senha do backup incorreta ou arquivo corrompido.");
        } finally {
            Arrays.fill(chave, (byte) 0);
        }
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

    private static byte[] pbkdf2(char[] senha, String saltB64, int iteracoes) {
        PBEKeySpec spec = new PBEKeySpec(senha, Base64.getDecoder().decode(saltB64), iteracoes, TAM_CHAVE_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        } finally {
            spec.clearPassword();   // o PBEKeySpec guarda uma copia propria do char[] da senha
        }
    }
}
