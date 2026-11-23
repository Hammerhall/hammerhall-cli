package io.hammerhall.cli;

import java.util.Locale;
import java.util.ResourceBundle;

/**
 * Все слова клиента — в одном файле, {@code messages.properties}.
 *
 * <h3>Зачем (0.2.0)</h3>
 *
 * До 0.2.0 каждая фраза была зашита строкой там, где печаталась. Для одного
 * языка это удобно, но англоязычная версия платформы заявлена — и тогда
 * перевод означал бы переписать все сообщения по всем классам. Перенести
 * их в набор ресурсов, пока их сотня, стоит час; потом — неделю (ревью
 * CLI №1, СР-14).
 *
 * <p>Сейчас набор один — русский, базовый. Английский — файл
 * {@code messages_en.properties} рядом: {@link ResourceBundle} сам выберет
 * его на английской системе, а для остальных останется русский.
 *
 * <h3>Формат</h3>
 *
 * Подстановки — как в {@link String#format}, в {@link Locale#ROOT}:
 * разделитель дробной части — точка на любой машине (вывод клиента
 * копируют в чат поддержки, и «4,5» там ни к чему — СР-3). Знак процента
 * в тексте — {@code %%}, всегда: строка проходит через формат даже без
 * подстановок, правило одно.
 *
 * <p>🔑 Ключ, которого нет в наборе, ловит тест ещё до сборки
 * ({@code MsgTest}): он сверяет все вызовы {@code Msg.t("…")} в исходниках
 * с файлом — и в обратную сторону, чтобы мёртвые фразы не копились.
 */
public final class Msg {

    private static final ResourceBundle BUNDLE = ResourceBundle.getBundle("io.hammerhall.cli.messages");

    private Msg() {
    }

    /** Фраза по ключу с подстановками. */
    public static String t(String key, Object... args) {
        return String.format(Locale.ROOT, BUNDLE.getString(key), args);
    }
}
