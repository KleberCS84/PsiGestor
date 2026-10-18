package br.com.kleber.psigestor.model;

public class Convenio {
    private Integer id;
    private String nome;
    private String registroAns;
    private double valorSessao;
    private int prazoPagamentoDias = 30;

    public Convenio() { }

    public Convenio(String nome, String registroAns, double valorSessao, int prazoPagamentoDias) {
        this.nome = nome;
        this.registroAns = registroAns;
        this.valorSessao = valorSessao;
        this.prazoPagamentoDias = prazoPagamentoDias;
    }

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getNome() { return nome; }
    public void setNome(String nome) { this.nome = nome; }
    public String getRegistroAns() { return registroAns; }
    public void setRegistroAns(String registroAns) { this.registroAns = registroAns; }
    public double getValorSessao() { return valorSessao; }
    public void setValorSessao(double valorSessao) { this.valorSessao = valorSessao; }
    public int getPrazoPagamentoDias() { return prazoPagamentoDias; }
    public void setPrazoPagamentoDias(int d) { this.prazoPagamentoDias = d; }

    @Override public String toString() { return nome; }
}
