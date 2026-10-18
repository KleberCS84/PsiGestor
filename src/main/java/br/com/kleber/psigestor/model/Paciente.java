package br.com.kleber.psigestor.model;

import java.time.LocalDate;
import java.time.Period;

public class Paciente {
    private Integer id;
    private String nome;
    private String cpf;
    private LocalDate dataNascimento;
    private String telefone;
    private String email;
    private String contatoEmergencia;
    private String responsavelLegal;
    private Integer convenioId;
    private String numeroCarteirinha;
    private boolean arquivado;
    private String anonimizadoEm;   // "yyyy-MM-dd HH:mm:ss"; null = nao anonimizado

    public Paciente() { }

    public Paciente(String nome, LocalDate dataNascimento, String telefone) {
        this.nome = nome;
        this.dataNascimento = dataNascimento;
        this.telefone = telefone;
    }

    /** Idade em anos completos na data informada (null se sem data de nascimento). */
    public Integer idadeEm(LocalDate data) {
        return dataNascimento == null ? null : Period.between(dataNascimento, data).getYears();
    }

    public boolean isMenorDeIdade(LocalDate hoje) {
        Integer idade = idadeEm(hoje);
        return idade != null && idade < 18;
    }

    public boolean fazAniversarioEm(LocalDate dia) {
        return dataNascimento != null
                && dataNascimento.getMonth() == dia.getMonth()
                && dataNascimento.getDayOfMonth() == dia.getDayOfMonth();
    }

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getNome() { return nome; }
    public void setNome(String nome) { this.nome = nome; }
    public String getCpf() { return cpf; }
    public void setCpf(String cpf) { this.cpf = cpf; }
    public LocalDate getDataNascimento() { return dataNascimento; }
    public void setDataNascimento(LocalDate d) { this.dataNascimento = d; }
    public String getTelefone() { return telefone; }
    public void setTelefone(String telefone) { this.telefone = telefone; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getContatoEmergencia() { return contatoEmergencia; }
    public void setContatoEmergencia(String c) { this.contatoEmergencia = c; }
    public String getResponsavelLegal() { return responsavelLegal; }
    public void setResponsavelLegal(String r) { this.responsavelLegal = r; }
    public Integer getConvenioId() { return convenioId; }
    public void setConvenioId(Integer convenioId) { this.convenioId = convenioId; }
    public String getNumeroCarteirinha() { return numeroCarteirinha; }
    public void setNumeroCarteirinha(String n) { this.numeroCarteirinha = n; }
    public boolean isArquivado() { return arquivado; }
    public void setArquivado(boolean arquivado) { this.arquivado = arquivado; }
    public String getAnonimizadoEm() { return anonimizadoEm; }
    public void setAnonimizadoEm(String anonimizadoEm) { this.anonimizadoEm = anonimizadoEm; }
    /** Anonimizado (LGPD) e definitivo: nao volta a ser editado, agendado nem desarquivado. */
    public boolean isAnonimizado() { return anonimizadoEm != null; }

    @Override public String toString() { return nome; }
}
