package br.com.kleber.psigestor.service;

import br.com.kleber.psigestor.model.Atendimento;
import br.com.kleber.psigestor.model.StatusAtendimento;
import br.com.kleber.psigestor.model.Usuario;

import java.time.LocalDate;
import java.util.*;

public class FinanceiroService {
    private final Contexto ctx;

    public record Resumo(double faturado, double recebido, double aReceber, long realizados,
                         long faltas, long cancelados, long recibosPendentes) {
        /** Taxa de faltas sobre sessoes que deveriam ter acontecido. */
        public double taxaFaltas() {
            long base = realizados + faltas;
            return base == 0 ? 0 : (double) faltas / base;
        }
    }

    public record LinhaRepasse(String profissional, long sessoes, double bruto, double parteClinica,
                               double liquidoProfissional) { }

    FinanceiroService(Contexto ctx) { this.ctx = ctx; }

    public Resumo resumo(LocalDate de, LocalDate ate) {
        ctx.exigirSessao();
        List<Atendimento> lista = ctx.agenda.periodo(de, ate, null);
        double faturado = 0, recebido = 0, aReceber = 0;
        long realizados = 0, faltas = 0, cancelados = 0, recibos = 0;
        for (Atendimento a : lista) {
            switch (a.getStatus()) {
                case REALIZADO -> {
                    realizados++;
                    faturado += a.getValor();
                    if (a.isPago()) recebido += a.getValor(); else aReceber += a.getValor();
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
        Map<Integer, double[]> soma = new LinkedHashMap<>(); // [sessoes, bruto]
        for (Atendimento a : ctx.agenda.periodo(de, ate, null)) {
            if (a.getStatus() != StatusAtendimento.REALIZADO) continue;
            double[] v = soma.computeIfAbsent(a.getProfissionalId(), k -> new double[2]);
            v[0]++;
            v[1] += a.getValor();
        }
        List<LinhaRepasse> linhas = new ArrayList<>();
        soma.forEach((id, v) -> {
            Usuario u = profs.get(id);
            double clinica = arred(v[1] * u.getPercentualRepasseClinica() / 100.0);
            linhas.add(new LinhaRepasse(u.getNome(), (long) v[0], arred(v[1]), clinica, arred(v[1] - clinica)));
        });
        linhas.sort(Comparator.comparingDouble(LinhaRepasse::bruto).reversed());
        return linhas;
    }

    private static double arred(double v) { return Math.round(v * 100.0) / 100.0; }
}
