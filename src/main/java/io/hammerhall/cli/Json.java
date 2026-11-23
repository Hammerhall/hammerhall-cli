package io.hammerhall.cli;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON: один настоящий разборщик и запись — без зависимостей.
 *
 * <h3>Почему так, а не Jackson</h3>
 *
 * Клиент — один jar без зависимостей: обёртка качает его при первом запуске.
 * Правило хорошее, и оно остаётся. Но до 0.2.0 оно съело другое правило —
 * «один источник истины»: разборщиков было ЧЕТЫРЕ ({@code Json} по полям
 * верхнего уровня и три заплатки в командах, вырезавшие вложенные объекты
 * подстрокой). Заплатки ломались от пробела после двоеточия, от массива
 * верхнего уровня, путали ключ со строковым значением и обрезали дробные
 * числа — молча, без ошибки (ревью CLI №1, АРХ-1, ВЫС-1, ВЫС-2, ВЫС-3, СР-12).
 *
 * <p>Здесь — обычный рекурсивный спуск по грамматике RFC 8259: дерево из
 * {@link Map} (порядок ключей — как прислал сервер), {@link List},
 * {@link String}, {@link BigDecimal}, {@link Boolean} и {@code null}.
 * Чтение — через {@link Node}: отсутствующее поле даёт не исключение
 * и не тихий ноль, а явный {@link Node#MISSING}, и вызывающий решает сам,
 * что для него значит «нет поля».
 *
 * <p>⚠️ Испорченный JSON — {@link IllegalArgumentException} с позицией:
 * его ловит {@link ApiClient} и говорит человеку «платформа ответила
 * не JSON», а не печатает стектрейс.
 */
public final class Json {

    private final String text;
    private int at;

    private Json(String text) {
        this.text = text;
    }

    /** Разобрать текст целиком. Мусор после значения — ошибка, а не «прочитал сколько смог». */
    public static Node parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("пустой ответ");
        }
        Json parser = new Json(text);
        parser.skipSpace();
        Object value = parser.value();
        parser.skipSpace();
        if (parser.at != text.length()) {
            throw parser.error("лишние символы после значения");
        }
        return new Node(value);
    }

    // ---------- разбор ----------

    private Object value() {
        if (at >= text.length()) {
            throw error("значение оборвано");
        }
        char c = text.charAt(at);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) {
                    yield number();
                }
                throw error("неожиданный символ '" + c + "'");
            }
        };
    }

    private Map<String, Object> object() {
        Map<String, Object> result = new LinkedHashMap<>();
        at++;   // {
        skipSpace();
        if (peek() == '}') {
            at++;
            return result;
        }
        while (true) {
            skipSpace();
            if (peek() != '"') {
                throw error("ожидался ключ в кавычках");
            }
            String key = string();
            skipSpace();
            expect(':');
            skipSpace();
            result.put(key, value());
            skipSpace();
            char next = peek();
            at++;
            if (next == '}') {
                return result;
            }
            if (next != ',') {
                throw error("ожидалась ',' или '}'");
            }
        }
    }

    private List<Object> array() {
        List<Object> result = new ArrayList<>();
        at++;   // [
        skipSpace();
        if (peek() == ']') {
            at++;
            return result;
        }
        while (true) {
            skipSpace();
            result.add(value());
            skipSpace();
            char next = peek();
            at++;
            if (next == ']') {
                return result;
            }
            if (next != ',') {
                throw error("ожидалась ',' или ']'");
            }
        }
    }

    private String string() {
        at++;   // открывающая кавычка
        StringBuilder result = new StringBuilder();
        while (at < text.length()) {
            char c = text.charAt(at++);
            if (c == '"') {
                return result.toString();
            }
            if (c == '\\') {
                if (at >= text.length()) {
                    break;
                }
                char escaped = text.charAt(at++);
                switch (escaped) {
                    case '"', '\\', '/' -> result.append(escaped);
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> {
                        // Суррогатная пара приходит двумя \\u подряд — и как два
                        // char складывается в один эмодзи сама, без особой ветки.
                        if (at + 4 > text.length()) {
                            throw error("оборванное \\u");
                        }
                        try {
                            result.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        } catch (NumberFormatException e) {
                            throw error("неверное \\u");
                        }
                        at += 4;
                    }
                    default -> throw error("неизвестная последовательность \\" + escaped);
                }
                continue;
            }
            if (c < 0x20) {
                throw error("управляющий символ внутри строки");
            }
            result.append(c);
        }
        throw error("строка не закрыта");
    }

    private BigDecimal number() {
        int start = at;
        if (peek() == '-') {
            at++;
        }
        while (at < text.length() && "0123456789.eE+-".indexOf(text.charAt(at)) >= 0) {
            at++;
        }
        try {
            return new BigDecimal(text.substring(start, at));
        } catch (NumberFormatException e) {
            at = start;
            throw error("неверное число");
        }
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, at)) {
            throw error("неверное значение");
        }
        at += word.length();
        return value;
    }

    private void expect(char c) {
        if (peek() != c) {
            throw error("ожидалось '" + c + "'");
        }
        at++;
    }

    private char peek() {
        return at < text.length() ? text.charAt(at) : '\0';
    }

    private void skipSpace() {
        while (at < text.length()) {
            char c = text.charAt(at);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return;
            }
            at++;
        }
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("JSON: " + what + " (позиция " + at + ")");
    }

    // ---------- запись ----------

    /**
     * Объект из пар «ключ, значение» — для тела запроса.
     *
     * <p>🔑 Экранируется КАЖДОЕ значение, а не одно избранное. До 0.2.0
     * тело {@code /submit} собиралось шаблоном, и экранировался только код:
     * {@code forgeId} и {@code taskId} уходили как есть, и имя каталога
     * с кавычкой ломало JSON (ревью CLI №1, ВЫС-9).
     */
    public static String object(Object... keysAndValues) {
        if (keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("нечётное число аргументов");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return write(map);
    }

    /** Записать дерево из {@link Map}, {@link List}, строк, чисел, булевых и {@code null}. */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        switch (value) {
            case null -> out.append("null");
            case String s -> out.append('"').append(escape(s)).append('"');
            case Boolean b -> out.append(b);
            case Number n -> out.append(n);
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    out.append('"').append(escape(String.valueOf(e.getKey()))).append("\":");
                    write(e.getValue(), out);
                }
                out.append('}');
            }
            case List<?> list -> {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    write(list.get(i), out);
                }
                out.append(']');
            }
            default -> throw new IllegalArgumentException("не умею записать " + value.getClass());
        }
    }

    /** Экранирование строки для отправки. */
    public static String escape(String raw) {
        StringBuilder result = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (c < 0x20) {
                        result.append(String.format("\\u%04x", (int) c));
                    } else {
                        result.append(c);
                    }
                }
            }
        }
        return result.toString();
    }

    // ---------- чтение дерева ----------

    /**
     * Узел дерева с безопасной навигацией: {@code body.get("tests").get("total").asLong(0)}.
     *
     * <p>Нет поля, не тот тип, не объект — получается {@link #MISSING},
     * и цепочка не падает на середине. Это не «тихий ноль»: вызывающий
     * явно пишет запасное значение и решает, что оно значит.
     */
    public record Node(Object value) {

        /** Отсутствующее значение — не то же, что JSON {@code null}. */
        public static final Node MISSING = new Node(Missing.INSTANCE);

        private enum Missing { INSTANCE }

        public boolean isMissing() {
            return value == Missing.INSTANCE;
        }

        public boolean isObject() {
            return value instanceof Map;
        }

        public boolean isArray() {
            return value instanceof List;
        }

        /** Поле объекта; не объект или нет поля — {@link #MISSING}. */
        public Node get(String key) {
            if (value instanceof Map<?, ?> map && map.containsKey(key)) {
                return new Node(map.get(key));
            }
            return MISSING;
        }

        /** Строка или {@code null}, если это не строка. */
        public String string() {
            return value instanceof String s ? s : null;
        }

        /** Строка; нет строки или она пустая — {@code null}. */
        public String text() {
            String s = string();
            return s == null || s.isBlank() ? null : s;
        }

        public long asLong(long fallback) {
            return value instanceof BigDecimal n ? Math.round(n.doubleValue()) : fallback;
        }

        public double asDouble(double fallback) {
            return value instanceof BigDecimal n ? n.doubleValue() : fallback;
        }

        public boolean isNumber() {
            return value instanceof BigDecimal;
        }

        public boolean asBool(boolean fallback) {
            return value instanceof Boolean b ? b : fallback;
        }

        /** Ключи объекта в порядке ответа; не объект — пусто. */
        public List<String> keys() {
            if (value instanceof Map<?, ?> map) {
                List<String> keys = new ArrayList<>(map.size());
                map.keySet().forEach(k -> keys.add(String.valueOf(k)));
                return keys;
            }
            return Collections.emptyList();
        }

        /** Элементы массива; не массив — пусто. */
        public List<Node> items() {
            if (value instanceof List<?> list) {
                List<Node> items = new ArrayList<>(list.size());
                list.forEach(item -> items.add(new Node(item)));
                return items;
            }
            return Collections.emptyList();
        }

        /** Строковые элементы массива; остальное пропускается. */
        public List<String> strings() {
            List<String> result = new ArrayList<>();
            for (Node item : items()) {
                if (item.string() != null) {
                    result.add(item.string());
                }
            }
            return result;
        }

        /** Компактная запись — для сообщений об ошибке, когда объяснения нет. */
        public String compact() {
            return isMissing() ? "" : write(value);
        }
    }
}
