package br.com.kleber.psigestor.service;

import br.com.kleber.psigestor.model.Atendimento;
import br.com.kleber.psigestor.model.StatusAtendimento;
import br.com.kleber.psigestor.model.Usuario;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/** Todos os valores em centavos (long): somas exatas, sem divergencia entre cards e tabela. */
public class FinanceiroService {
    private static final BigDecimal CEM = BigDecimal.valueOf(100);

    private final Contexto ctx;

    public record Resumo(long faturado, long recebido, long aReceber, long realizados,
                         long faltas, long cancelados, long recibosPendentes) {
        /** Taxa de faltas sobre sessoes que deveriam ter acontecido. */
        public double taxaFaltas() {
            long base = realizados + faltas;
            return base == 0 ? 0 : (double) faltas / base;
        }
    }

    public record LinhaRepasse(String profissional, long sessoes, long bruto, long parteClinica,
                               long liquidoProfissional) { }

    FinanceiroService(Contexto ctx) { this.ctx = ctx; }

    public Resumo resumo(LocalDate de, LocalDate ate) {
        ctx.exigirSessao();
        List<Atendimento> lista = ctx.agenda.periodo(de, ate, null);
        long faturado = 0, recebido = 0, aReceber = 0;
        long realizados = 0, faltas = 0, cancelados = 0, recibos = 0;
        for (Atendimento a : lista) {
            switch (a.getStatus()) {
                case REALIZADO -> {
                    realizados++;
                    faturado += a.getValorCentavos();
                    if (a.isPago()) recebido += a.getValorCentavos(); else aReceber += a.getValorCentavos();
                    // recibo do Receita Saude vale para atendimento particular pago a pessoa fisica
                    if (a.isPago() && a.getConvenioId() == null && !a.isReciboEmitido()) recibos++;
                }
                case FALTOU -> faltas++;
                case CANCELADO -> cancelados++;
                default -> { }
            }
        }
        return new Resumo(faturado, recebido, aReceber, realizados, faltas, cancelados, recibos);
    }

    /** Repasse por profissional: a clinica retem o percentual configurado no cadastro do psicologo. */
    public List<LinhaRepasse> repasse(LocalDate de, LocalDate ate) {
        ctx.exigirSessao();
        Map<Integer, Usuario> profs = new HashMap<>();
        ctx.usuarios.listar().forEach(u -> profs.put(u.getId(), u));
        Map<Integer, long[]> soma = new LinkedHashMap<>(); // [sessoes, bruto]
        for (Atendimento a : ctx.agenda.periodo(de, ate, null)) {
            if (a.getStatus() != StatusAtendimento.REALIZADO) continue;
            long[] v = soma.computeIfAbsent(a.getProfissionalId(), k -> new long[2]);
            v[0]++;
            v[1] += a.getValorCentavos();
        }
        List<LinhaRepasse> linhas = new ArrayList<>();
        soma.forEach((id, v) -> {
            Usuario u = profs.get(id);
            long clinica = parteClinica(v[1], u.getPercentualRepasseClinica());
            // liquido por subtracao: clinica + profissional sempre fecha com o bruto
            linhas.add(new LinhaRepasse(u.getNome(), v[0], v[1], clinica, v[1] - clinica));
        });
        linhas.sort(Comparator.comparingLong(LinhaRepasse::bruto).reversed());
        return linhas;
    }

    /** Arredondamento bancario (HALF_EVEN): meio centavo nao favorece sempre o mesmo lado. */
    static long parteClinica(long brutoCentavos, double percentual) {
        return BigDecimal.valueOf(brutoCentavos)
                .multiply(BigDecimal.valueOf(percentual))
                .divide(CEM, 0, RoundingMode.HALF_EVEN)
                .longValueExact();
    }
}
