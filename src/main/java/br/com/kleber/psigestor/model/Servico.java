package br.com.kleber.psigestor.model;

public class Servico {
    private Integer id;
    private String nome;
    private String codigoTuss;
    private int duracaoMin = 50;
    private double valor;

    public Servico() { }

    public Servico(String nome, String codigoTuss, int duracaoMin, double valor) {
        this.nome = nome;
        this.codigoTuss = codigoTuss;
        this.duracaoMin = duracaoMin;
        this.valor = valor;
    }

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getNome() { return nome; }
    public void setNome(String nome) { this.nome = nome; }
    public String getCodigoTuss() { return codigoTuss; }
    public void setCodigoTuss(String codigoTuss) { this.codigoTuss = codigoTuss; }
    public int getDuracaoMin() { return duracaoMin; }
    public void setDuracaoMin(int duracaoMin) { this.duracaoMin = duracaoMin; }
    public double getValor() { return valor; }
    public void setValor(double valor) { this.valor = valor; }

    @Override public String toString() { return nome + " (" + duracaoMin + " min)"; }
}
