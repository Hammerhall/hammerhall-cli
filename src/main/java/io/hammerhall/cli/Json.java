package io.hammerhall.cli;

import java.util.ArrayList;
import java.util.List;

/**
 * Чтение полей верхнего уровня из JSON — ровно столько, сколько нужно клиенту.
 *
 * <h3>Почему не Jackson</h3>
 *
 * Клиент должен оставаться jar без зависимостей: обёртка качает его при
 * первом запуске, и лишние мегабайты там ни к чему. А читаем мы ответы,
 * которые сами же формируем на сервере — их форма известна и стабильна.
 *
 * <h3>⚠️ Чего этот класс НЕ умеет</h3>
 *
 * Это не парсер JSON и не претендует им быть. Он не разбирает вложенные
 * объекты произвольной глубины, не проверяет синтаксис, не сообщает
 * об ошибках формата. Если ответ платформы усложнится — заводить Jackson,
 * а не достраивать это.
 *
 * <p>Что умеет: достать строку, число и массив строк по имени поля
 * с учётом экранирования и вложенных структур, которые надо пропустить.
 */
public final class Json {

    private Json() {
    }

    /** Строковое поле верхнего уровня. Нет поля — {@code null}. */
    public static String string(String json, String field) {
        int valueAt = valuePosition(json, field);
        if (valueAt < 0 || json.charAt(valueAt) != '"') {
            return null;
        }
        StringBuilder result = new StringBuilder();
        for (int i = valueAt + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                i++;
                result.append(unescape(json.charAt(i), json, i));
                if (json.charAt(i) == 'u') {
                    i += 4;
                }
                continue;
            }
            if (c == '"') {
                return result.toString();
            }
            result.append(c);
        }
        return null;
    }

    /** Числовое поле верхнего уровня. Нет поля или не число — {@code fallback}. */
    public static long number(String json, String field, long fallback) {
        int valueAt = valuePosition(json, field);
        if (valueAt < 0) {
            return fallback;
        }
        int end = valueAt;
        while (end < json.length() && "-0123456789".indexOf(json.charAt(end)) >= 0) {
            end++;
        }
        if (end == valueAt) {
            return fallback;
        }
        try {
            return Long.parseLong(json.substring(valueAt, end));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Булево поле. Нет поля или значение не {@code true}/{@code false} — {@code fallback}.
     * <p>
     * Отдельным методом, потому что {@link #string} на булевом вернёт
     * {@code null}: значение не в кавычках, и разбор строки его не увидит.
     */
    public static boolean bool(String json, String field, boolean fallback) {
        int valueAt = valuePosition(json, field);
        if (valueAt < 0) {
            return fallback;
        }
        if (json.startsWith("true", valueAt)) {
            return true;
        }
        if (json.startsWith("false", valueAt)) {
            return false;
        }
        return fallback;
    }

    /** Массив строк. Нет поля — пустой список. */
    public static List<String> strings(String json, String field) {
        List<String> result = new ArrayList<>();
        int valueAt = valuePosition(json, field);
        if (valueAt < 0 || json.charAt(valueAt) != '[') {
            return result;
        }

        boolean inString = false;
        StringBuilder current = new StringBuilder();
        for (int i = valueAt + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (c == '\\' && i + 1 < json.length()) {
                    i++;
                    current.append(unescape(json.charAt(i), json, i));
                    if (json.charAt(i) == 'u') {
                        i += 4;
                    }
                    continue;
                }
                if (c == '"') {
                    result.add(current.toString());
                    current.setLength(0);
                    inString = false;
                    continue;
                }
                current.append(c);
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == ']') {
                return result;
            }
        }
        return result;
    }

    /**
     * Позиция ЗНАЧЕНИЯ поля верхнего уровня.
     *
     * <p>Ключевое здесь — считать глубину. Поле {@code "status"} может
     * встретиться и внутри вложенного объекта; без учёта глубины мы бы
     * взяли первое попавшееся и молча вернули не то. Так же пропускаем
     * содержимое строк: {@code "log": "поле \"status\" внутри текста"}
     * не должно сойти за поле.
     */
    private static int valuePosition(String json, String field) {
        String needle = '"' + field + '"';
        int depth = 0;
        boolean inString = false;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);

            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            switch (c) {
                case '"' -> {
                    if (depth == 1 && json.startsWith(needle, i)) {
                        int colon = json.indexOf(':', i + needle.length());
                        if (colon > 0) {
                            int valueAt = colon + 1;
                            while (valueAt < json.length()
                                    && Character.isWhitespace(json.charAt(valueAt))) {
                                valueAt++;
                            }
                            return valueAt;
                        }
                    }
                    inString = true;
                }
                case '{', '[' -> depth++;
                case '}', ']' -> depth--;
                default -> {
                    // прочие символы значения нас не интересуют
                }
            }
        }
        return -1;
    }

    private static String unescape(char escaped, String json, int at) {
        return switch (escaped) {
            case 'n' -> "\n";
            case 't' -> "\t";
            case 'r' -> "\r";
            case 'b' -> "\b";
            case 'f' -> "\f";
            case 'u' -> at + 4 < json.length()
                    ? String.valueOf((char) Integer.parseInt(json.substring(at + 1, at + 5), 16))
                    : "";
            default -> String.valueOf(escaped);
        };
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
}
