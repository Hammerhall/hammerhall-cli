package io.hammerhall.cli;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Идентификаторы кузницы и задачи — одно правило на все команды.
 *
 * <h3>Зачем (0.2.0)</h3>
 *
 * До 0.2.0 регулярка задачи жила в {@code pull}, а {@code submit} брал
 * номер из имени файла без проверки; кузница из имени каталога не
 * проверялась вовсе. Каталог {@code hammerhall "рабочий"} или файл
 * {@code MovCode0002 (копия).java} ломали тело запроса, пробел в пути давал
 * стектрейс из {@code URI.create} (ревью CLI №1, ВЫС-9, СР-5).
 *
 * <p>Правило сознательно мягче платформенного ({@code [A-Z][a-z]{2}(Code|Mind)\d{4}}):
 * решает сервер, клиент лишь не пускает то, что сломает запрос или путь.
 * Но мягкость теперь одинаковая во всех командах.
 */
public final class Ids {

    /** Кузница: {@code stream-forge}, {@code spring-context}. */
    public static final Pattern FORGE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    /** Задача: {@code MovCode0002}. Это же имя Java-класса — без точек и дефисов. */
    public static final Pattern TASK = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");

    private Ids() {
    }

    public static String forge(String raw) {
        if (raw == null || !FORGE.matcher(raw).matches()) {
            throw new CliException(Msg.t("ids.forge.bad", raw));
        }
        return raw;
    }

    public static String task(String raw) {
        if (raw == null || !TASK.matcher(raw).matches()) {
            throw new CliException(Msg.t("ids.task.bad", raw));
        }
        return raw;
    }

    /**
     * Сегмент пути URL. Проверенные идентификаторы кодировать нечего, но
     * сегмент кодируется всегда: правило «путь собирается из закодированных
     * частей» дешевле, чем помнить, какая часть была проверена.
     */
    public static String segment(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
