package br.com.kleber.psigestor.util;

import br.com.kleber.psigestor.model.Usuario;

import javax.crypto.SecretKey;

/** Usuario logado + chave do prontuario (somente em memoria, apenas para psicologos). */
public record Sessao(Usuario usuario, SecretKey chaveProntuario) {

    public boolean podeLerProntuario() {
        return usuario.isPsicologo() && chaveProntuario != null;
    }
}
