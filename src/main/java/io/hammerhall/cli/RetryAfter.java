package io.hammerhall.cli;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * {@code Retry-After}: через сколько повторять — как сказал сервер.
 *
 * <h3>Две формы (0.2.0)</h3>
 *
 * RFC 9110 разрешает и секунды, и HTTP-дату, и прокси перед сервером
 * вправе ответить датой. До 0.2.0 клиент печатал
 * заголовок как есть и добавлял «с.»: «Повторите через Wed, 05 Aug 2026
 * 14:32:11 GMT с.» (ревью CLI №1, ВЫС-11). Теперь — секунды, иначе дата
 * минус «сейчас», иначе «не знаю» — и тогда фраза без числа.
 *
 * <p>И срок печатается по-человечески: квота выдачи задач отвечает
 * «через 43 200 с» — это «через 12 ч», и так и надо сказать.
 */
public final class RetryAfter {

    private RetryAfter() {
    }

    /** Срок из заголовка или {@code null}, если заголовка нет или он непонятен. */
    public static Duration parse(String header, Clock clock) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String value = header.trim();
        if (value.chars().allMatch(Character::isDigit)) {
            try {
                return Duration.ofSeconds(Long.parseLong(value));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        try {
            ZonedDateTime at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
            Duration left = Duration.between(clock.instant(), at.toInstant());
            return left.isNegative() ? Duration.ZERO : left;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** «5 с», «3 мин», «2 ч 10 мин», «1 сут 3 ч». */
    public static String human(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        if (seconds < 60) {
            return Msg.t("time.seconds", seconds);
        }
        long minutes = (seconds + 59) / 60;   // вверх: «через 1 мин» лучше, чем обещать раньше
        if (minutes < 60) {
            return Msg.t("time.minutes", minutes);
        }
        long hours = minutes / 60;
        long restMinutes = minutes % 60;
        if (hours < 24) {
            return restMinutes == 0 ? Msg.t("time.hours", hours) : Msg.t("time.hours.minutes", hours, restMinutes);
        }
        long days = hours / 24;
        long restHours = hours % 24;
        return restHours == 0 ? Msg.t("time.days", days) : Msg.t("time.days.hours", days, restHours);
    }
}
