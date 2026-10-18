package br.com.kleber.psigestor.model;

public class Convenio {
    private Integer id;
    private String nome;
    private String registroAns;
    private long valorSessaoCentavos;   // centavos (M7)
    private int prazoPagamentoDias = 30;

    public Convenio() { }

    public Convenio(String nome, String registroAns, long valorSessaoCentavos, int prazoPagamentoDias) {
        this.nome = nome;
        this.registroAns = registroAns;
        this.valorSessaoCentavos = valorSessaoCentavos;
        this.prazoPagamentoDias = prazoPagamentoDias;
    }

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getNome() { return nome; }
    public void setNome(String nome) { this.nome = nome; }
    public String getRegistroAns() { return registroAns; }
    public void setRegistroAns(String registroAns) { this.registroAns = registroAns; }
    public long getValorSessaoCentavos() { return valorSessaoCentavos; }
    public void setValorSessaoCentavos(long v) { this.valorSessaoCentavos = v; }
    public int getPrazoPagamentoDias() { return prazoPagamentoDias; }
    public void setPrazoPagamentoDias(int d) { this.prazoPagamentoDias = d; }

    @Override public String toString() { return nome; }
}
