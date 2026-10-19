package br.com.kleber.psigestor.model;

public class Usuario {
    private Integer id;
    private String nome;
    private String login;
    private String senhaHash;
    private String senhaSalt;
    private String chaveSalt;
    private byte[] chaveCifrada;   // DEK embrulhada pela senha; null = formato antigo (antes da v1)
    private byte[] chaveIv;
    private int iteracoes;         // PBKDF2 usado no hash da senha e na KEK
    private Papel papel;
    private String crp;
    private double percentualRepasseClinica;
    private boolean ativo = true;
    private byte[] dekRecuperacao;        // IV || DEK cifrada pelo codigo de recuperacao; null = nao gerado
    private String saltRecuperacao;
    private Integer iteracoesRecuperacao;
    private int termosVersao;             // versao dos termos de uso aceita; 0 = nunca aceitou
    private String termosAceitosEm;

    public Usuario() { }

    public Usuario(String nome, String login, Papel papel, String crp) {
        this.nome = nome;
        this.login = login;
        this.papel = papel;
        this.crp = crp;
    }

    public boolean isPsicologo() { return papel == Papel.PSICOLOGO; }

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getNome() { return nome; }
    public void setNome(String nome) { this.nome = nome; }
    public String getLogin() { return login; }
    public void setLogin(String login) { this.login = login; }
    public String getSenhaHash() { return senhaHash; }
    public void setSenhaHash(String senhaHash) { this.senhaHash = senhaHash; }
    public String getSenhaSalt() { return senhaSalt; }
    public void setSenhaSalt(String senhaSalt) { this.senhaSalt = senhaSalt; }
    public String getChaveSalt() { return chaveSalt; }
    public void setChaveSalt(String chaveSalt) { this.chaveSalt = chaveSalt; }
    public byte[] getChaveCifrada() { return chaveCifrada; }
    public void setChaveCifrada(byte[] chaveCifrada) { this.chaveCifrada = chaveCifrada; }
    public byte[] getChaveIv() { return chaveIv; }
    public void setChaveIv(byte[] chaveIv) { this.chaveIv = chaveIv; }
    public int getIteracoes() { return iteracoes; }
    public void setIteracoes(int iteracoes) { this.iteracoes = iteracoes; }
    public Papel getPapel() { return papel; }
    public void setPapel(Papel papel) { this.papel = papel; }
    public String getCrp() { return crp; }
    public void setCrp(String crp) { this.crp = crp; }
    public double getPercentualRepasseClinica() { return percentualRepasseClinica; }
    public void setPercentualRepasseClinica(double p) { this.percentualRepasseClinica = p; }
    public boolean isAtivo() { return ativo; }
    public void setAtivo(boolean ativo) { this.ativo = ativo; }
    public byte[] getDekRecuperacao() { return dekRecuperacao; }
    public void setDekRecuperacao(byte[] dekRecuperacao) { this.dekRecuperacao = dekRecuperacao; }
    public String getSaltRecuperacao() { return saltRecuperacao; }
    public void setSaltRecuperacao(String saltRecuperacao) { this.saltRecuperacao = saltRecuperacao; }
    public Integer getIteracoesRecuperacao() { return iteracoesRecuperacao; }
    public void setIteracoesRecuperacao(Integer iteracoesRecuperacao) { this.iteracoesRecuperacao = iteracoesRecuperacao; }
    public int getTermosVersao() { return termosVersao; }
    public void setTermosVersao(int termosVersao) { this.termosVersao = termosVersao; }
    public String getTermosAceitosEm() { return termosAceitosEm; }
    public void setTermosAceitosEm(String termosAceitosEm) { this.termosAceitosEm = termosAceitosEm; }
    public boolean temRecuperacao() {
        return dekRecuperacao != null && saltRecuperacao != null && iteracoesRecuperacao != null;
    }

    @Override public String toString() {
        return crp == null || crp.isBlank() ? nome : nome + " (CRP " + crp + ")";
    }
}
