package io.hammerhall.cli;

import java.io.PrintStream;
import java.nio.charset.CharsetEncoder;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Вывод. Отдельным классом, чтобы команды не звали System.out напрямую —
 * иначе их не проверить тестом.
 *
 * <h3>🔴 Цвет — только настоящему терминалу</h3>
 *
 * Escape-коды в файле или конвейере — мусор. Признак терминала —
 * {@link java.io.Console#isTerminal()}: {@code System.console() != null}
 * с JDK 22 верен и при перенаправлении. Признак приходит СНАРУЖИ: его
 * вычисляет {@code Main} — там же, где по нему выбирается кодировка потока.
 *
 * <p>С 0.2.0 цвет выключают ещё {@code NO_COLOR} (де-факто стандарт) и флаг
 * {@code --no-color} (ревью CLI №1, СР-14). И признак терминала доступен
 * командам: {@code login} по нему решает, скрыт ли ввод пароля (ВЫС-7).
 *
 * <h3>🔴 Символы, которых нет в кодировке приёмника</h3>
 *
 * В консоли Windows вывод попадает в cp866, в старых — в cp1251. Длинного
 * тире, кавычек-ёлочек, значков {@code ✓ ⚠ ✗} и блока полоски {@code █}
 * там нет: незакодируемый символ стал бы {@code ?}. Поэтому — вопрос
 * кодировщику о каждом символе и замена непроходимого на ASCII.
 *
 * <p>С 0.2.0 перебор идёт по КОДОВЫМ ТОЧКАМ, а не по {@code char}: эмодзи —
 * суррогатная пара, и по половинкам каждая становилась {@code ?}, то есть
 * один эмодзи — двумя знаками вопроса. Лог проверки приходит с сервера,
 * а туда попадает вывод программы ученика — {@code System.out.println("🔥")}
 * вопрос времени (СР-13). И полоска профиля {@code █} — в таблице замен
 * и в пробном наборе: в cp1251 её нет, профиль превращался в вопросы (СР-2).
 *
 * <p>⚠️ В UTF-8 не тратим ничего: {@code encoder == null}, строки уходят
 * как есть, типографика сохраняется.
 */
public class Console {

    private static final String RESET = "\u001B[0m";
    private static final String RED = "\u001B[31m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String DIM = "\u001B[2m";

    /** Пробный набор: переживёт его кодировка — переживёт всё, что мы печатаем. */
    static final String PROBE = "✓⚠✗—«»…→·█▓▒░";

    /**
     * Чем заменять непроходимое. Замены — чтобы фраза осталась читаемой:
     * тире остаётся тире, стрелка стрелкой. Ключ — кодовая точка: так
     * выразима замена и за пределами BMP.
     */
    private static final Map<Integer, String> SUBSTITUTES = Map.ofEntries(
            Map.entry((int) '—', "-"), Map.entry((int) '–', "-"), Map.entry((int) '−', "-"),
            Map.entry((int) '«', "\""), Map.entry((int) '»', "\""),
            Map.entry((int) '“', "\""), Map.entry((int) '”', "\""), Map.entry((int) '„', "\""),
            Map.entry((int) '‘', "'"), Map.entry((int) '’', "'"),
            Map.entry((int) '…', "..."),
            Map.entry((int) '→', "->"), Map.entry((int) '←', "<-"),
            Map.entry((int) '·', "."), Map.entry((int) '•', "*"),
            Map.entry((int) '✓', "v"), Map.entry((int) '✔', "v"),
            Map.entry((int) '✗', "x"), Map.entry((int) '✘', "x"),
            Map.entry((int) '⚠', "!"),
            Map.entry((int) '█', "#"), Map.entry((int) '▓', "#"),
            Map.entry((int) '▒', "+"), Map.entry((int) '░', "."),
            Map.entry((int) '≥', ">="), Map.entry((int) '≤', "<="), Map.entry((int) '×', "x"),
            Map.entry(0x00A0, " "));

    private final PrintStream out;
    private final PrintStream err;
    private final boolean terminal;
    private final boolean colored;

    /** Кодировщик приёмника или {@code null}, если он переживает всё. */
    private final CharsetEncoder encoder;

    private final String okMark;
    private final String warnMark;
    private final String failMark;

    /** О чём уже предупредили в этом запуске. */
    private final Set<String> warned = new HashSet<>();

    /** Не терминал, без цвета — для тестов и конвейеров. */
    public Console(PrintStream out, PrintStream err) {
        this(out, err, false, false);
    }

    /**
     * @param terminal вывод — настоящий терминал (а не файл или труба)
     * @param colored  красить ли: терминал, и цвет не выключен
     */
    public Console(PrintStream out, PrintStream err, boolean terminal, boolean colored) {
        this.out = out;
        this.err = err;
        this.terminal = terminal;
        this.colored = colored;
        this.encoder = encoderFor(out);

        boolean unicode = encoder == null;
        this.okMark = unicode ? "✓ " : "[ok] ";
        this.warnMark = unicode ? "⚠ " : "[!] ";
        this.failMark = unicode ? "✗ " : "[x] ";
    }

    /** Кодировщик потока, либо {@code null} — если проверять нечего. */
    private static CharsetEncoder encoderFor(PrintStream stream) {
        try {
            CharsetEncoder probe = stream.charset().newEncoder();
            return probe.canEncode(PROBE) ? null : probe;
        } catch (UnsupportedOperationException e) {
            return null;
        }
    }

    /** Вывод идёт в настоящий терминал: там ввод можно скрыть, а цвет уместен. */
    public boolean isTerminal() {
        return terminal;
    }

    /** Привести строку к тому, что приёмник в состоянии показать. */
    String adapt(String text) {
        if (encoder == null || text == null) {
            return text;
        }
        StringBuilder rebuilt = new StringBuilder(text.length());
        text.codePoints().forEach(point -> {
            if (encoder.canEncode(new String(Character.toChars(point)))) {
                rebuilt.appendCodePoint(point);
            } else {
                rebuilt.append(SUBSTITUTES.getOrDefault(point, "?"));
            }
        });
        return rebuilt.toString();
    }

    public void print(String text) {
        out.println(adapt(text));
    }

    /** Напечатать как есть, без перевода строки: для многострочной справки. */
    public void block(String text) {
        out.print(adapt(text));
    }

    /** То же — в поток ошибок: справка при неверном вызове. */
    public void blockErr(String text) {
        err.print(adapt(text));
    }

    /**
     * Приглашение к вводу — на той же строке, без перевода.
     * <p>До 0.2.0 «Почта: » печаталось с переводом строки, а «Пароль: » —
     * без: разнобой в первом же диалоге, который видит человек (СР-14).
     */
    public void prompt(String text) {
        out.print(adapt(text));
        out.flush();
    }

    public void success(String text) {
        out.println(paint(GREEN, okMark) + adapt(text));
    }

    public void warn(String text) {
        out.println(paint(YELLOW, warnMark) + adapt(text));
    }

    /** Предупредить один раз за запуск — например, что клиент устарел: не на каждый запрос. */
    public void warnOnce(String key, String text) {
        if (warned.add(key)) {
            warn(text);
        }
    }

    public void error(String text) {
        err.println(paint(RED, failMark) + adapt(text));
    }

    public void dim(String text) {
        out.println(paint(DIM, adapt(text)));
    }

    /** Трасса непредвиденной ошибки — в поток ошибок: это баг клиента, и он нужен целиком. */
    public void stackTrace(Throwable failure) {
        failure.printStackTrace(err);
    }

    /** Служебная строка в поток ошибок — для {@code --verbose}: вывод команды не засоряет. */
    public void trace(String text) {
        err.println(paint(DIM, adapt(text)));
    }

    private String paint(String color, String text) {
        return colored ? color + text + RESET : text;
    }
}
