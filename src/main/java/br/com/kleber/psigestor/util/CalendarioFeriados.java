package br.com.kleber.psigestor.util;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Feriados nacionais do Brasil calculados no codigo (sem API e sem internet) e os alertas de agendamento:
 * fim de semana, feriado, vespera de feriado e dia seguinte a feriado. Classe pura, sem estado e sem JavaFX.
 * Feriados estaduais e municipais nao sao cobertos.
 */
public final class CalendarioFeriados {
    private static final DateTimeFormatter DIA_MES = DateTimeFormatter.ofPattern("dd/MM");
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private CalendarioFeriados() {
    }

    /** Domingo de Pascoa pelo algoritmo de Meeus/Jones/Butcher (calendario gregoriano). */
    public static LocalDate pascoa(int ano) {
        int a = ano % 19;
        int b = ano / 100;
        int c = ano % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int mes = (h + l - 7 * m + 114) / 31;
        int dia = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(ano, mes, dia);
    }

    /** Nome do feriado nacional da data, se houver. */
    public static Optional<String> feriado(LocalDate d) {
        String fixo = switch (d.getMonthValue() * 100 + d.getDayOfMonth()) {
            case 101 -> "Confraternização Universal";
            case 421 -> "Tiradentes";
            case 501 -> "Dia do Trabalho";
            case 907 -> "Independência do Brasil";
            case 1012 -> "Nossa Senhora Aparecida";
            case 1102 -> "Finados";
            case 1115 -> "Proclamação da República";
            case 1120 -> "Consciência Negra";
            case 1225 -> "Natal";
            default -> null;
        };
        if (fixo != null) return Optional.of(fixo);
        LocalDate pascoa = pascoa(d.getYear());
        if (d.equals(pascoa.minusDays(48))) return Optional.of("Carnaval (segunda-feira)");
        if (d.equals(pascoa.minusDays(47))) return Optional.of("Carnaval (terça-feira)");
        if (d.equals(pascoa.minusDays(2))) return Optional.of("Sexta-feira Santa");
        if (d.equals(pascoa.plusDays(60))) return Optional.of("Corpus Christi");
        return Optional.empty();
    }

    /** Motivos de atencao para agendar na data; lista vazia = sem alerta. So informa, nunca bloqueia. */
    public static List<String> alertas(LocalDate d) {
        List<String> motivos = new ArrayList<>();
        if (d.getDayOfWeek() == DayOfWeek.SATURDAY) motivos.add("É sábado");
        if (d.getDayOfWeek() == DayOfWeek.SUNDAY) motivos.add("É domingo");
        feriado(d).ifPresent(nome -> motivos.add(d.format(DIA_MES) + " é feriado (" + nome + ")"));
        if (feriado(d.plusDays(1)).isPresent()) motivos.add(d.format(DIA_MES) + " é véspera de feriado");
        if (feriado(d.minusDays(1)).isPresent()) motivos.add(d.format(DIA_MES) + " é dia seguinte a feriado");
        return motivos;
    }

    /** Uma linha "dd/MM/yyyy: motivo; motivo" por data com alerta; vazio se nenhuma tiver. */
    public static String resumo(List<LocalDate> datas) {
        StringBuilder sb = new StringBuilder();
        for (LocalDate d : datas) {
            List<String> motivos = alertas(d);
            if (motivos.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(d.format(DATA)).append(": ").append(String.join("; ", motivos));
        }
        return sb.toString();
    }
}
