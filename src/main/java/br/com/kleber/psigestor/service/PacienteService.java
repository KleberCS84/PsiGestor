package br.com.kleber.psigestor.service;

import br.com.kleber.psigestor.model.Atendimento;
import br.com.kleber.psigestor.model.Consentimento;
import br.com.kleber.psigestor.model.Paciente;
import br.com.kleber.psigestor.model.TipoConsentimento;
import br.com.kleber.psigestor.util.RegraNegocioException;
import br.com.kleber.psigestor.util.Validadores;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class PacienteService {
    private static final DateTimeFormatter BR = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter CARIMBO = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    static final String ANONIMIZADO = "Paciente anonimizado (LGPD): o cadastro nao pode ser alterado nem reativado.";
    /** Guarda minima do registro documental (Res. CFP 01/2009) antes de anonimizar. */
    static final int GUARDA_MINIMA_ANOS = 5;
    static final int NOME_MAX = 120;
    static final int TELEFONE_MAX = 20;
    static final int EMAIL_MAX = 120;
    static final int CONTATO_MAX = 150;
    static final int RESPONSAVEL_MAX = 120;
    static final int CARTEIRINHA_MAX = 40;
    /** Nascimento mais antigo aceito: evita ano digitado errado (ex.: 1099 em vez de 1999). */
    static final int IDADE_MAXIMA = 130;
    private final Contexto ctx;

    PacienteService(Contexto ctx) { this.ctx = ctx; }

    public Paciente salvar(Paciente p) {
        ctx.exigirSessao();
        if (p == null) throw new RegraNegocioException("Informe os dados do paciente.");
        // confere no banco: a tela edita uma copia, que pode nao trazer anonimizado_em
        if (p.getId() != null && buscar(p.getId()).isAnonimizado())
            throw new RegraNegocioException(ANONIMIZADO);
        if (Validadores.vazio(p.getNome())) throw new RegraNegocioException("Nome do paciente e obrigatorio.");
        p.setNome(limitar(p.getNome(), NOME_MAX, "Nome"));
        p.setTelefone(opcional(p.getTelefone(), TELEFONE_MAX, "Telefone"));
        p.setEmail(opcional(p.getEmail(), EMAIL_MAX, "E-mail"));
        p.setContatoEmergencia(opcional(p.getContatoEmergencia(), CONTATO_MAX, "Contato de emergencia"));
        p.setResponsavelLegal(opcional(p.getResponsavelLegal(), RESPONSAVEL_MAX, "Responsavel legal"));
        p.setNumeroCarteirinha(opcional(p.getNumeroCarteirinha(), CARTEIRINHA_MAX, "N. carteirinha"));
        if (p.getTelefone() != null && !Validadores.telefoneValido(p.getTelefone()))
            throw new RegraNegocioException("Telefone invalido. Informe DDD + numero, ex.: (62) 99999-0000.");

        // Minimizacao (LGPD art. 6, III): CPF e opcional; se informado, precisa ser valido e unico.
        if (Validadores.vazio(p.getCpf())) {
            p.setCpf(null);
        } else {
            if (!Validadores.cpfValido(p.getCpf())) throw new RegraNegocioException("CPF invalido.");
            p.setCpf(Validadores.somenteDigitos(p.getCpf()));
            if (ctx.pacientes.cpfEmUso(p.getCpf(), p.getId()))
                throw new RegraNegocioException("Ja existe paciente com esse CPF.");
        }
        LocalDate hoje = ctx.hoje();
        if (p.getDataNascimento() != null && p.getDataNascimento().isAfter(hoje))
            throw new RegraNegocioException("Data de nascimento no futuro.");
        if (p.getDataNascimento() != null && p.getDataNascimento().isBefore(hoje.minusYears(IDADE_MAXIMA)))
            throw new RegraNegocioException("Data de nascimento invalida (mais de " + IDADE_MAXIMA + " anos).");
        // Menor de idade: atendimento depende de autorizacao do responsavel (ECA / Codigo de Etica, art. 8).
        if (p.isMenorDeIdade(hoje) && Validadores.vazio(p.getResponsavelLegal()))
            throw new RegraNegocioException("Paciente menor de 18 anos: informe o responsavel legal.");
        if (p.getEmail() != null && !p.getEmail().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))
            throw new RegraNegocioException("E-mail invalido.");
        // mensagem clara em vez do erro de FK do banco
        if (p.getConvenioId() != null
                && ctx.convenios.listar().stream().noneMatch(c -> c.getId().equals(p.getConvenioId())))
            throw new RegraNegocioException("Convenio nao encontrado.");

        boolean novo = p.getId() == null;
        try {
            ctx.transacao(() -> {
                ctx.pacientes.salvar(p);
                ctx.auditar(novo ? "CADASTRO_PACIENTE" : "ALTERACAO_PACIENTE", "paciente", p.getId());
            });
        } catch (RuntimeException e) {
            if (novo) p.setId(null);   // o INSERT foi desfeito: o id preenchido pelo DAO nao existe
            throw e;
        }
        return p;
    }

    /** Campo obrigatorio: sem espacos nas pontas e dentro do limite da tela/relatorio. */
    private static String limitar(String valor, int maximo, String campo) {
        String v = valor.strip();
        if (v.length() > maximo)
            throw new RegraNegocioException(campo + ": maximo de " + maximo + " caracteres.");
        return v;
    }

    /** Campo opcional: em branco vira null. */
    private static String opcional(String valor, int maximo, String campo) {
        return Validadores.vazio(valor) ? null : limitar(valor, maximo, campo);
    }

    /**
     * Nao existe "excluir paciente": o registro documental deve ser guardado por no minimo
     * 5 anos (Res. CFP 01/2009). Arquivar tira o paciente das listas do dia a dia.
     */
    public void arquivar(int pacienteId, boolean arquivado) {
        ctx.exigirSessao();
        Paciente p = buscar(pacienteId);
        if (p.isAnonimizado()) throw new RegraNegocioException(ANONIMIZADO);
        p.setArquivado(arquivado);
        ctx.transacao(() -> {
            ctx.pacientes.salvar(p);
            ctx.auditar(arquivado ? "ARQUIVAMENTO_PACIENTE" : "REATIVACAO_PACIENTE", "paciente", pacienteId);
        });
    }

    /**
     * Anonimiza o cadastro do titular (LGPD art. 18, IV) depois da guarda minima de 5 anos
     * (Res. CFP 01/2009), contada do ultimo atendimento ou, se nunca houve, do cadastro.
     * Nao toca no prontuario, nos atendimentos nem no financeiro. Ver docs/POLITICA_DADOS.md.
     */
    public void anonimizarPaciente(int pacienteId) {
        if (!ctx.exigirSessao().usuario().isPsicologo())
            throw new RegraNegocioException("Somente psicologos podem anonimizar pacientes.");
        if (buscar(pacienteId).isAnonimizado()) throw new RegraNegocioException("Paciente ja foi anonimizado.");
        LocalDate permitidoApos = ctx.pacientes.ultimaReferencia(pacienteId).plusYears(GUARDA_MINIMA_ANOS);
        if (!permitidoApos.isBefore(ctx.hoje()))
            throw new RegraNegocioException("Anonimizacao so e permitida " + GUARDA_MINIMA_ANOS
                    + " anos apos o ultimo atendimento (a partir de " + permitidoApos.plusDays(1).format(DATA) + ").");
        ctx.transacao(() -> {
            ctx.pacientes.anonimizar(pacienteId);
            ctx.auditar("ANONIMIZACAO_PACIENTE", "paciente", pacienteId);
        });
    }

    public Paciente buscar(int id) {
        ctx.exigirSessao();
        return ctx.pacientes.buscar(id).orElseThrow(() -> new RegraNegocioException("Paciente nao encontrado."));
    }

    public List<Paciente> pesquisar(String nome, boolean incluirArquivados) {
        ctx.exigirSessao();
        return ctx.pacientes.pesquisar(nome, incluirArquivados);
    }

    public List<Paciente> aniversariantes(LocalDate dia) {
        ctx.exigirSessao();
        return ctx.pacientes.pesquisar("", false).stream().filter(p -> p.fazAniversarioEm(dia)).toList();
    }

    public void registrarConsentimento(int pacienteId, TipoConsentimento tipo, boolean concedido, String obs) {
        ctx.exigirSessao();
        if (tipo == null) throw new RegraNegocioException("Informe o tipo de consentimento.");
        buscar(pacienteId);   // paciente inexistente: mensagem clara em vez de erro de FK
        ctx.transacao(() -> {
            ctx.consentimentos.registrar(pacienteId, tipo, concedido, obs);
            ctx.auditar((concedido ? "CONSENTIMENTO_" : "REVOGACAO_") + tipo.name(), "paciente", pacienteId);
        });
    }

    public boolean temConsentimento(int pacienteId, TipoConsentimento tipo) {
        ctx.exigirSessao();
        return ctx.consentimentos.vigente(pacienteId, tipo);
    }

    public List<Consentimento> consentimentos(int pacienteId) {
        ctx.exigirSessao();
        return ctx.consentimentos.historico(pacienteId);
    }

    /**
     * Atende ao direito de acesso/portabilidade do titular (LGPD art. 18, II e V):
     * gera um TXT com dados cadastrais, consentimentos e historico de agenda.
     * O conteudo do prontuario NAO entra aqui: o acesso a ele e feito pelo psicologo,
     * que decide a forma adequada (Res. CFP 01/2009 e 06/2019).
     */
    public Path exportarDadosDoTitular(int pacienteId, Path pasta) {
        if (!ctx.exigirSessao().usuario().isPsicologo())
            throw new RegraNegocioException("Exportacao de dados do titular restrita ao psicologo.");
        Paciente p = buscar(pacienteId);
        StringBuilder sb = new StringBuilder();
        sb.append("RELATORIO DE DADOS PESSOAIS - LGPD art. 18\n");
        sb.append("Gerado em: ").append(LocalDateTime.now().format(BR)).append("\n\n");
        sb.append("== Dados cadastrais ==\n");
        sb.append("Nome: ").append(p.getNome()).append('\n');
        sb.append("CPF: ").append(nvl(p.getCpf())).append('\n');
        sb.append("Nascimento: ").append(p.getDataNascimento() == null ? "-" : p.getDataNascimento()).append('\n');
        sb.append("Telefone: ").append(nvl(p.getTelefone())).append('\n');
        sb.append("E-mail: ").append(nvl(p.getEmail())).append('\n');
        sb.append("Contato de emergencia: ").append(nvl(p.getContatoEmergencia())).append('\n');
        sb.append("Responsavel legal: ").append(nvl(p.getResponsavelLegal())).append("\n\n");
        sb.append("== Consentimentos (mais recente primeiro) ==\n");
        for (Consentimento c : consentimentos(pacienteId)) {
            sb.append(c.registradoEm().format(BR)).append(" | ").append(c.tipo().getDescricao())
              .append(" | ").append(c.concedido() ? "CONCEDIDO" : "NEGADO/REVOGADO").append('\n');
        }
        sb.append("\n== Atendimentos ==\n");
        for (Atendimento a : ctx.atendimentos.listarPorPaciente(pacienteId)) {
            sb.append(a.getInicio().format(BR)).append(" | ").append(a.getServicoNome())
              .append(" | ").append(a.getStatus()).append(" | ").append(a.getModalidade()).append('\n');
        }
        try {
            Files.createDirectories(pasta);
            // nome sem o id do paciente (nao previsivel) e CREATE_NEW: nunca sobrescreve outro relatorio
            Path arq = pasta.resolve("dados_titular_" + LocalDateTime.now().format(CARIMBO) + "_"
                    + BackupService.sufixoAleatorio() + ".txt");
            // log antes do arquivo, na mesma transacao: arquivo que nao foi gravado desfaz o log
            ctx.transacao(() -> {
                ctx.auditar("EXPORTACAO_DADOS_TITULAR", "paciente", pacienteId);
                try {
                    Files.writeString(arq, sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            return arq;
        } catch (IOException e) {
            throw new RegraNegocioException("Nao foi possivel gravar o arquivo: " + e.getMessage());
        } catch (UncheckedIOException e) {
            throw new RegraNegocioException("Nao foi possivel gravar o arquivo: " + e.getCause().getMessage());
        }
    }

    private static String nvl(String s) { return Validadores.vazio(s) ? "-" : s; }
}
