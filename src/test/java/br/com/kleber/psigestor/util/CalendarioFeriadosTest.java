package br.com.kleber.psigestor.util;

import br.com.kleber.psigestor.service.AgendaService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Feriados nacionais e alertas de agendamento: puro, datas ficticias fixas, sem banco. */
class CalendarioFeriadosTest {

    private static LocalDate d(int ano, int mes, int dia) {
        return LocalDate.of(ano, mes, dia);
    }

    // ---------- pascoa e feriados moveis ----------

    @Test
    void pascoaDeAnosDiferentes() {
        assertEquals(d(2025, 4, 20), CalendarioFeriados.pascoa(2025));
        assertEquals(d(2026, 4, 5), CalendarioFeriados.pascoa(2026));
        assertEquals(d(2027, 3, 28), CalendarioFeriados.pascoa(2027));
    }

    @Test
    void feriadosMoveisDe2025() {
        assertEquals("Carnaval (segunda-feira)", CalendarioFeriados.feriado(d(2025, 3, 3)).orElseThrow());
        assertEquals("Carnaval (terça-feira)", CalendarioFeriados.feriado(d(2025, 3, 4)).orElseThrow());
        assertEquals("Sexta-feira Santa", CalendarioFeriados.feriado(d(2025, 4, 18)).orElseThrow());
        assertEquals("Corpus Christi", CalendarioFeriados.feriado(d(2025, 6, 19)).orElseThrow());
    }

    @Test
    void feriadosMoveisDe2026() {
        assertEquals("Carnaval (segunda-feira)", CalendarioFeriados.feriado(d(2026, 2, 16)).orElseThrow());
        assertEquals("Carnaval (terça-feira)", CalendarioFeriados.feriado(d(2026, 2, 17)).orElseThrow());
        assertEquals("Sexta-feira Santa", CalendarioFeriados.feriado(d(2026, 4, 3)).orElseThrow());
        assertEquals("Corpus Christi", CalendarioFeriados.feriado(d(2026, 6, 4)).orElseThrow());
    }

    @Test
    void feriadosMoveisDe2027() {
        assertEquals("Carnaval (segunda-feira)", CalendarioFeriados.feriado(d(2027, 2, 8)).orElseThrow());
        assertEquals("Carnaval (terça-feira)", CalendarioFeriados.feriado(d(2027, 2, 9)).orElseThrow());
        assertEquals("Sexta-feira Santa", CalendarioFeriados.feriado(d(2027, 3, 26)).orElseThrow());
        assertEquals("Corpus Christi", CalendarioFeriados.feriado(d(2027, 5, 27)).orElseThrow());
    }

    @Test
    void feriadosFixos() {
        int[][] fixos = {{1, 1}, {4, 21}, {5, 1}, {9, 7}, {10, 12}, {11, 2}, {11, 15}, {11, 20}, {12, 25}};
        for (int[] f : fixos) {
            assertTrue(CalendarioFeriados.feriado(d(2026, f[0], f[1])).isPresent(), f[1] + "/" + f[0]);
        }
        assertEquals("Proclamação da República", CalendarioFeriados.feriado(d(2026, 11, 15)).orElseThrow());
        assertTrue(CalendarioFeriados.feriado(d(2026, 11, 16)).isEmpty());
    }

    // ---------- alertas ----------

    @Test
    void dataComumNaoTemAlerta() {
        assertTrue(CalendarioFeriados.alertas(d(2026, 11, 11)).isEmpty());   // quarta
    }

    @Test
    void sabadoEVesperaDeFeriado() {
        // 14/11/2026 = sabado, e 15/11 e feriado
        assertEquals(List.of("É sábado", "14/11 é véspera de feriado"), CalendarioFeriados.alertas(d(2026, 11, 14)));
    }

    @Test
    void domingoEFeriado() {
        // 15/11/2026 = domingo e feriado
        assertEquals(List.of("É domingo", "15/11 é feriado (Proclamação da República)"),
                CalendarioFeriados.alertas(d(2026, 11, 15)));
    }

    @Test
    void vesperaDeFeriadoEmDiaUtil() {
        // 19/11/2026 = quinta, 20/11 e feriado
        assertEquals(List.of("19/11 é véspera de feriado"), CalendarioFeriados.alertas(d(2026, 11, 19)));
    }

    @Test
    void diaDoFeriadoEmDiaUtil() {
        // 20/11/2026 = sexta
        assertEquals(List.of("20/11 é feriado (Consciência Negra)"), CalendarioFeriados.alertas(d(2026, 11, 20)));
    }

    @Test
    void diaSeguinteAFeriadoCaindoNoSabado() {
        // 21/11/2026 = sabado, dia seguinte a 20/11
        assertEquals(List.of("É sábado", "21/11 é dia seguinte a feriado"), CalendarioFeriados.alertas(d(2026, 11, 21)));
    }

    @Test
    void diaSeguinteEmDiaUtil() {
        // 16/11/2026 = segunda, dia seguinte a 15/11
        assertEquals(List.of("16/11 é dia seguinte a feriado"), CalendarioFeriados.alertas(d(2026, 11, 16)));
    }

    @Test
    void viradaDeAno() {
        assertEquals(List.of("31/12 é véspera de feriado"), CalendarioFeriados.alertas(d(2026, 12, 31)));   // quinta
        // 01/01/2027 = sexta e feriado; 02/01/2027 = sabado, dia seguinte
        assertEquals(List.of("É sábado", "02/01 é dia seguinte a feriado"), CalendarioFeriados.alertas(d(2027, 1, 2)));
    }

    @Test
    void feriadoMovelGeraAlertaNaVespera() {
        // 02/04/2026 (quinta) e vespera da Sexta-feira Santa
        assertEquals(List.of("02/04 é véspera de feriado"), CalendarioFeriados.alertas(d(2026, 4, 2)));
    }

    // ---------- lote ----------

    @Test
    void loteSemanalComAlgunsDiasDeAlerta() {
        // sextas a partir de 30/10/2026: so 20/11 (feriado) tem alerta
        LocalDateTime primeira = LocalDateTime.of(2026, 10, 30, 9, 0);
        List<LocalDate> datas = AgendaService.gerarDatas(primeira, AgendaService.Frequencia.SEMANAL, 5)
                .stream().map(LocalDateTime::toLocalDate).toList();
        assertEquals(List.of(d(2026, 10, 30), d(2026, 11, 6), d(2026, 11, 13), d(2026, 11, 20), d(2026, 11, 27)), datas);
        assertEquals("20/11/2026: 20/11 é feriado (Consciência Negra)", CalendarioFeriados.resumo(datas));
    }

    @Test
    void resumoComVariasDatasUneLinhasEIgnoraAsSemAlerta() {
        String r = CalendarioFeriados.resumo(List.of(d(2026, 11, 11), d(2026, 11, 14), d(2026, 11, 15)));
        assertEquals("14/11/2026: É sábado; 14/11 é véspera de feriado\n"
                + "15/11/2026: É domingo; 15/11 é feriado (Proclamação da República)", r);
    }

    @Test
    void loteSemAlertaDevolveResumoVazio() {
        assertEquals("", CalendarioFeriados.resumo(List.of(d(2026, 11, 11), d(2026, 11, 18))));
    }
}
