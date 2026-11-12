package br.com.kleber.psigestor.model;

import java.time.LocalDateTime;

public class Atendimento {
    private Integer id;
    private int pacienteId;
    private int profissionalId;
    private int servicoId;
    private Integer convenioId;
    private LocalDateTime inicio;
    private LocalDateTime fim;
    private StatusAtendimento status = StatusAtendimento.AGENDADO;
    private Modalidade modalidade = Modalidade.PRESENCIAL;
    private long valorCentavos;   // dinheiro sempre em centavos (M7)
    private boolean pago;
    private String formaPagamento;
    private boolean reciboEmitido;
    /** % da clinica vigente quando a sessao foi realizada; null enquanto nao realizada (M-5). */
    private Double percentualRepasseClinica;
    /** Administrativa e em claro (secretaria ve): nada clinico, que vai cifrado no prontuario. */
    private String observacao;

    /** Agendamento em lote: UUID da serie; null = atendimento avulso. */
    private String serieId;
    private Integer numeroNaSerie;
    /** Somente leitura: quantas sessoes a serie tem (inclui canceladas); 0 = avulso. */
    private int totalNaSerie;

    // Campos apenas para exibicao (preenchidos por JOIN no DAO)
    private String pacienteNome;
    private String profissionalNome;
    private String servicoNome;

    /** Dois intervalos [a,b) e [c,d) se sobrepoem quando a < d e c < b. */
    public boolean conflitaCom(LocalDateTime outroInicio, LocalDateTime outroFim) {
        return inicio.isBefore(outroFim) && outroInicio.isBefore(fim);
    }

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public int getPacienteId() { return pacienteId; }
    public void setPacienteId(int pacienteId) { this.pacienteId = pacienteId; }
    public int getProfissionalId() { return profissionalId; }
    public void setProfissionalId(int profissionalId) { this.profissionalId = profissionalId; }
    public int getServicoId() { return servicoId; }
    public void setServicoId(int servicoId) { this.servicoId = servicoId; }
    public Integer getConvenioId() { return convenioId; }
    public void setConvenioId(Integer convenioId) { this.convenioId = convenioId; }
    public LocalDateTime getInicio() { return inicio; }
    public void setInicio(LocalDateTime inicio) { this.inicio = inicio; }
    public LocalDateTime getFim() { return fim; }
    public void setFim(LocalDateTime fim) { this.fim = fim; }
    public StatusAtendimento getStatus() { return status; }
    public void setStatus(StatusAtendimento status) { this.status = status; }
    public Modalidade getModalidade() { return modalidade; }
    public void setModalidade(Modalidade modalidade) { this.modalidade = modalidade; }
    public long getValorCentavos() { return valorCentavos; }
    public void setValorCentavos(long valorCentavos) { this.valorCentavos = valorCentavos; }
    public boolean isPago() { return pago; }
    public void setPago(boolean pago) { this.pago = pago; }
    public String getFormaPagamento() { return formaPagamento; }
    public void setFormaPagamento(String f) { this.formaPagamento = f; }
    public boolean isReciboEmitido() { return reciboEmitido; }
    public void setReciboEmitido(boolean r) { this.reciboEmitido = r; }
    public Double getPercentualRepasseClinica() { return percentualRepasseClinica; }
    public void setPercentualRepasseClinica(Double p) { this.percentualRepasseClinica = p; }
    public String getObservacao() { return observacao; }
    public void setObservacao(String observacao) { this.observacao = observacao; }
    public String getSerieId() { return serieId; }
    public void setSerieId(String serieId) { this.serieId = serieId; }
    public Integer getNumeroNaSerie() { return numeroNaSerie; }
    public void setNumeroNaSerie(Integer n) { this.numeroNaSerie = n; }
    public int getTotalNaSerie() { return totalNaSerie; }
    public void setTotalNaSerie(int t) { this.totalNaSerie = t; }
    public boolean isDeSerie() { return serieId != null; }
    /** "sessão 3 de 10"; vazio se nao faz parte de uma serie. */
    public String rotuloSerie() {
        return serieId == null || numeroNaSerie == null ? "" : "sessão " + numeroNaSerie + " de " + totalNaSerie;
    }
    public String getPacienteNome() { return pacienteNome; }
    public void setPacienteNome(String n) { this.pacienteNome = n; }
    public String getProfissionalNome() { return profissionalNome; }
    public void setProfissionalNome(String n) { this.profissionalNome = n; }
    public String getServicoNome() { return servicoNome; }
    public void setServicoNome(String n) { this.servicoNome = n; }
}
