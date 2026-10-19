package br.com.kleber.psigestor.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Termos de uso aceitos no primeiro acesso de cada usuario. Mudou o texto? Crie
 * /termos/termos-v&lt;N+1&gt;.md e suba VERSAO_ATUAL: todos aceitam de novo no proximo login.
 */
public final class Termos {
    public static final int VERSAO_ATUAL = 1;

    private Termos() { }

    /** Texto da versao atual; recurso ausente e erro de empacotamento, nao pode passar em silencio. */
    public static String texto() {
        String recurso = "/termos/termos-v" + VERSAO_ATUAL + ".md";
        try (InputStream in = Termos.class.getResourceAsStream(recurso)) {
            if (in == null) throw new IllegalStateException("Recurso nao encontrado: " + recurso);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
