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

    @Override public String toString() {
        return crp == null || crp.isBlank() ? nome : nome + " (CRP " + crp + ")";
    }
}
