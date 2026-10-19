package br.com.kleber.psigestor.model;

/** Perfil de acesso. Secretaria NAO le prontuario (sigilo - Codigo de Etica, art. 9). */
public enum Papel {
    PSICOLOGO, SECRETARIA;

    /** Texto de tela; o banco guarda name(). */
    public String rotulo() {
        return switch (this) {
            case PSICOLOGO -> "Psicólogo(a)";
            case SECRETARIA -> "Secretário(a)";
        };
    }
}
