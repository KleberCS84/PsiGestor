package br.com.kleber.psigestor.model;

public class Servico {
    private Integer id;
    private String nome;
    private String codigoTuss;
    private int duracaoMin = 50;
    private long valorCentavos;   // dinheiro sempre em centavos (M7)

    public Servico() { }

    public Servico(String nome, String codigoTuss, int duracaoMin, long valorCentavos) {
        this.nome = nome;
        this.codigoTuss = codigoTuss;
        this.duracaoMin = duracaoMin;
        this.valorCentavos = valorCentavos;
    }

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getNome() { return nome; }
    public void setNome(String nome) { this.nome = nome; }
    public String getCodigoTuss() { return codigoTuss; }
    public void setCodigoTuss(String codigoTuss) { this.codigoTuss = codigoTuss; }
    public int getDuracaoMin() { return duracaoMin; }
    public void setDuracaoMin(int duracaoMin) { this.duracaoMin = duracaoMin; }
    public long getValorCentavos() { return valorCentavos; }
    public void setValorCentavos(long valorCentavos) { this.valorCentavos = valorCentavos; }

    @Override public String toString() { return nome + " (" + duracaoMin + " min)"; }
}
