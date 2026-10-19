package br.com.kleber.psigestor.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** Versao do programa, vinda do pom.xml (o Maven filtra /versao.properties no build). */
public final class Versao {
    public static final String DESCONHECIDA = "desconhecida";
    public static final String REPOSITORIO = "https://github.com/KleberCS84/PsiGestor";

    private Versao() { }

    public static String atual() {
        try (InputStream in = Versao.class.getResourceAsStream("/versao.properties")) {
            if (in == null) return DESCONHECIDA;
            Properties p = new Properties();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8)); // load(InputStream) seria ISO-8859-1
            String v = p.getProperty("versao", "").trim();
            // sem filtragem (ex.: rodando direto da IDE) o valor seria o literal ${project.version}
            return v.isEmpty() || v.startsWith("${") ? DESCONHECIDA : v;
        } catch (IOException e) {
            return DESCONHECIDA;
        }
    }
}
