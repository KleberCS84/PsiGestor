package br.com.kleber.psigestor.service;

import br.com.kleber.psigestor.dao.EvolucaoDAO.LinhaCifrada;
import br.com.kleber.psigestor.model.Evolucao;
import br.com.kleber.psigestor.util.Criptografia;
import br.com.kleber.psigestor.util.RegraNegocioException;
import br.com.kleber.psigestor.util.Sessao;
import br.com.kleber.psigestor.util.Validadores;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Registro documental (prontuario). Regras de sigilo implementadas:
 *  1. so PSICOLOGO escreve/le (secretaria nao tem chave);
 *  2. cada registro e cifrado com a chave do AUTOR - outro psicologo da clinica nao le;
 *  3. nada e editado/apagado: correcoes viram "retificacao" (append-only + triggers no banco);
 *  4. toda leitura e gravacao vai para o log de acesso.
 */
public class ProntuarioService {
    private static final DateTimeFormatter SQLITE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public static final String SIGILOSO = "[Registro de outro profissional - conteudo sigiloso]";
    private final Contexto ctx;

    ProntuarioService(Contexto ctx) { this.ctx = ctx; }

    public int registrar(int pacienteId, Integer atendimentoId, String texto) {
        return gravar(pacienteId, atendimentoId, texto, null);
    }

    public int retificar(int pacienteId, int evolucaoOriginalId, String textoCorrigido) {
        return gravar(pacienteId, null, textoCorrigido, evolucaoOriginalId);
    }

    private int gravar(int pacienteId, Integer atendimentoId, String texto, Integer retificaId) {
        Sessao s = exigirPsicologo();
        if (Validadores.vazio(texto)) throw new RegraNegocioException("O registro esta vazio.");
        ctx.pacienteService.buscar(pacienteId);
        byte[][] ivECifrado = Criptografia.cifrar(texto.strip(), s.chaveProntuario());
        int id = ctx.evolucoes.inserir(pacienteId, atendimentoId, s.usuario().getId(),
                ivECifrado[1], ivECifrado[0], retificaId);
        ctx.auditar(retificaId == null ? "REGISTRO_PRONTUARIO" : "RETIFICACAO_PRONTUARIO", "paciente", pacienteId);
        return id;
    }

    public List<Evolucao> listar(int pacienteId) {
        Sessao s = exigirPsicologo();
        List<LinhaCifrada> linhas = ctx.evolucoes.listarPorPaciente(pacienteId);
        ctx.auditar("LEITURA_PRONTUARIO", "paciente", pacienteId);
        int eu = s.usuario().getId();
        return linhas.stream().map(l -> new Evolucao(
                l.id(), l.pacienteId(), l.atendimentoId(), l.profissionalId(),
                LocalDateTime.parse(l.registradoEm(), SQLITE),
                l.profissionalId() == eu ? Criptografia.decifrar(l.conteudo(), l.iv(), s.chaveProntuario()) : SIGILOSO,
                l.retificaId())).toList();
    }

    private Sessao exigirPsicologo() {
        Sessao s = ctx.exigirSessao();
        if (!s.podeLerProntuario())
            throw new RegraNegocioException("Acesso ao prontuario restrito ao psicologo (sigilo profissional).");
        return s;
    }
}
