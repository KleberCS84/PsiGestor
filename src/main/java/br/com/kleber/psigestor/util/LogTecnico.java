package br.com.kleber.psigestor.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Log tecnico local para diagnosticar erros inesperados (nao confundir com a trilha de auditoria log_acesso).
 * NUNCA grava dados de paciente: so a classe e a pilha das excecoes. A mensagem de uma excecao pode
 * conter nome, CPF ou texto digitado, por isso fica de fora - exceto a do driver SQLite (codigo/constraint).
 */
public final class LogTecnico {
    private static final Logger LOG = Logger.getLogger("br.com.kleber.psigestor");
    private static final String NL = System.lineSeparator();
    private static FileHandler arquivo;

    private LogTecnico() { }

    /** Grava em pasta/psigestor-N.log (ate 5 arquivos de 1 MB). Falha aqui nao impede o app de abrir. */
    public static synchronized void iniciar(Path pasta) {
        try {
            Files.createDirectories(pasta);
            FileHandler novo = new FileHandler(pasta.resolve("psigestor-%g.log").toString(), 1_000_000, 5, true);
            novo.setEncoding("UTF-8");
            novo.setFormatter(new SimpleFormatter());
            if (arquivo != null) {
                LOG.removeHandler(arquivo);
                arquivo.close();
            }
            arquivo = novo;
            LOG.addHandler(novo);
            LOG.setUseParentHandlers(false);
        } catch (IOException | RuntimeException e) {
            // sem arquivo: o logger continua no console padrao
        }
    }

    /** Fecha o arquivo (no Windows ele fica travado enquanto aberto). */
    public static synchronized void encerrar() {
        if (arquivo == null) return;
        LOG.removeHandler(arquivo);
        arquivo.close();
        arquivo = null;
    }

    /** Registra um erro inesperado; "onde" deve ser um texto fixo do codigo, nunca um dado digitado. */
    public static synchronized void erro(String onde, Throwable t) {
        LOG.severe(onde + NL + descrever(t));
        if (arquivo != null) arquivo.flush();
    }

    /** Classe + pilha de toda a cadeia de causas, sem as mensagens (exceto de SQLException). */
    public static String descrever(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Set<Throwable> vistos = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable atual = t; atual != null && vistos.add(atual); atual = atual.getCause()) {
            if (atual != t) sb.append("Causado por: ");
            sb.append(atual.getClass().getName());
            if (atual instanceof SQLException sql) sb.append(": ").append(sql.getMessage());
            sb.append(NL);
            for (StackTraceElement el : atual.getStackTrace()) sb.append("\tat ").append(el).append(NL);
        }
        return sb.toString();
    }
}
