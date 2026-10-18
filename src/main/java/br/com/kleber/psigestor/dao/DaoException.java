package br.com.kleber.psigestor.dao;

/** Converte SQLException (checada) em excecao de tempo de execucao. */
public class DaoException extends RuntimeException {
    public DaoException(String msg, Throwable causa) { super(msg + ": " + causa.getMessage(), causa); }
}
