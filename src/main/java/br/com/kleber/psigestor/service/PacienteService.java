package br.com.kleber.psigestor.service;

import br.com.kleber.psigestor.model.Atendimento;
import br.com.kleber.psigestor.model.Consentimento;
import br.com.kleber.psigestor.model.Paciente;
import br.com.kleber.psigestor.model.TipoConsentimento;
import br.com.kleber.psigestor.util.RegraNegocioException;
import br.com.kleber.psigestor.util.Validadores;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class PacienteService {
    private static final DateTimeFormatter BR = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private final Contexto ctx;

    PacienteService(Contexto ctx) { this.ctx = ctx; }

    public Paciente salvar(Paciente p) {
        ctx.exigirSessao();
        if (Validadores.vazio(p.getNome())) throw new RegraNegocioException("Nome do paciente e obrigatorio.");
        p.setNome(p.getNome().trim());

        // Minimizacao (LGPD art. 6, III): CPF e opcional; se informado, precisa ser valido e unico.
        if (Validadores.vazio(p.getCpf())) {
            p.setCpf(null);
        } else {
            if (!Validadores.cpfValido(p.getCpf())) throw new RegraNegocioException("CPF invalido.");
            p.setCpf(Validadores.somenteDigitos(p.getCpf()));
            if (ctx.pacientes.cpfEmUso(p.getCpf(), p.getId()))
                throw new RegraNegocioException("Ja existe paciente com esse CPF.");
        }
        if (p.getDataNascimento() != null && p.getDataNascimento().isAfter(LocalDate.now()))
            throw new RegraNegocioException("Data de nascimento no futuro.");
        // Menor de idade: atendimento depende de autorizacao do responsavel (ECA / Codigo de Etica, art. 8).
        if (p.isMenorDeIdade(LocalDate.now()) && Validadores.vazio(p.getResponsavelLegal()))
            throw new RegraNegocioException("Paciente menor de 18 anos: informe o responsavel legal.");
        if (!Validadores.vazio(p.getEmail()) && !p.getEmail().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))
            throw new RegraNegocioException("E-mail invalido.");

        boolean novo = p.getId() == null;
        ctx.pacientes.salvar(p);
        ctx.auditar(novo ? "CADASTRO_PACIENTE" : "ALTERACAO_PACIENTE", "paciente", p.getId());
        return p;
    }

    /**
     * Nao existe "excluir paciente": o registro documental deve ser guardado por no minimo
     * 5 anos (Res. CFP 01/2009). Arquivar tira o paciente das listas do dia a dia.
     */
    public void arquivar(int pacienteId, boolean arquivado) {
        ctx.exigirSessao();
        Paciente p = buscar(pacienteId);
        p.setArquivado(arquivado);
        ctx.pacientes.salvar(p);
        ctx.auditar(arquivado ? "ARQUIVAMENTO_PACIENTE" : "REATIVACAO_PACIENTE", "paciente", pacienteId);
    }

    public Paciente buscar(int id) {
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
        ctx.consentimentos.registrar(pacienteId, tipo, concedido, obs);
        ctx.auditar((concedido ? "CONSENTIMENTO_" : "REVOGACAO_") + tipo.name(), "paciente", pacienteId);
    }

    public boolean temConsentimento(int pacienteId, TipoConsentimento tipo) {
        return ctx.consentimentos.vigente(pacienteId, tipo);
    }

    public List<Consentimento> consentimentos(int pacienteId) {
        return ctx.consentimentos.historico(pacienteId);
    }

    /**
     * Atende ao direito de acesso/portabilidade do titular (LGPD art. 18, II e V):
     * gera um TXT com dados cadastrais, consentimentos e historico de agenda.
     * O conteudo do prontuario NAO entra aqui: o acesso a ele e feito pelo psicologo,
     * que decide a forma adequada (Res. CFP 01/2009 e 06/2019).
     */
    public Path exportarDadosDoTitular(int pacienteId, Path pasta) {
        ctx.exigirSessao();
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
            Path arq = pasta.resolve("dados_paciente_" + pacienteId + ".txt");
            Files.writeString(arq, sb.toString(), StandardCharsets.UTF_8);
            ctx.auditar("EXPORTACAO_DADOS_TITULAR", "paciente", pacienteId);
            return arq;
        } catch (IOException e) {
            throw new RegraNegocioException("Nao foi possivel gravar o arquivo: " + e.getMessage());
        }
    }

    private static String nvl(String s) { return Validadores.vazio(s) ? "-" : s; }
}
