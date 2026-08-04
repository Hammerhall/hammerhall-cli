package io.hammerhall.cli;

import java.io.PrintStream;
import java.nio.charset.CharsetEncoder;
import java.util.Map;

/**
 * Вывод. Отдельным классом, чтобы команды не звали System.out напрямую —
 * иначе их не проверить тестом.
 *
 * <h3>🔴 Цвет — только настоящему терминалу</h3>
 *
 * Escape-коды в файле или конвейере — мусор. Раньше признаком служило
 * {@code System.console() != null}, и это было верно до JDK 22: с этой
 * версии метод возвращает объект ДАЖЕ при перенаправлении, а терминал
 * отличает {@link java.io.Console#isTerminal()}. Проверка тихо перестала
 * проверять, и в консоль Windows лезло {@code ←[31m}.
 *
 * <p>🔑 Признак приходит СНАРУЖИ: его вычисляет {@code Main} — там же,
 * где по нему выбирается кодировка потока. Один ответ на оба вопроса.
 *
 * <h3>🔴 Символы, которых нет в кодировке приёмника</h3>
 *
 * В консоли Windows вывод попадает в cp866. Кириллица там есть, а длинного
 * тире, кавычек-ёлочек и значков {@code ✓ ⚠ ✗} нет: незакодируемый символ
 * превращается в {@code ?}, и человек читает «Hammerhall ? клиент кузницы»
 * и «?Забыли пароль??».
 *
 * <p>Первая попытка чинила это подстановкой трёх известных значков —
 * и провалилась ровно там, где список известного кончился. Поэтому теперь
 * не список, а **вопрос кодировщику о каждом символе**: непроходимый
 * заменяется на ASCII-эквивалент. Новая строка с новым символом лечится
 * сама, без правки этого класса.
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

    /**
     * Чем заменять непроходимое.
     *
     * <p>Замены выбраны так, чтобы фраза осталась читаемой, а не просто
     * перестала ломаться: тире остаётся тире, кавычки кавычками, стрелка
     * стрелкой. {@code ?} на их месте — это потеря смысла, а не оформления.
     */
    private static final Map<Character, String> SUBSTITUTES = Map.ofEntries(
            Map.entry('—', "-"), Map.entry('–', "-"), Map.entry('−', "-"),
            Map.entry('«', "\""), Map.entry('»', "\""),
            Map.entry('“', "\""), Map.entry('”', "\""), Map.entry('„', "\""),
            Map.entry('‘', "'"), Map.entry('’', "'"),
            Map.entry('…', "..."),
            Map.entry('→', "->"), Map.entry('←', "<-"),
            Map.entry('·', "*"), Map.entry('•', "*"),
            Map.entry('✓', "v"), Map.entry('✔', "v"),
            Map.entry('✗', "x"), Map.entry('✘', "x"),
            Map.entry('⚠', "!"),
            Map.entry('≥', ">="), Map.entry('≤', "<="), Map.entry('×', "x"),
            Map.entry('\u00A0', " "));

    private final PrintStream out;
    private final PrintStream err;
    private final boolean colored;

    /** Кодировщик приёмника или {@code null}, если он переживает всё. */
    private final CharsetEncoder encoder;

    private final String okMark;
    private final String warnMark;
    private final String failMark;

    /**
     * Без цвета. Признак терминала обязан приходить снаружи — его вычисляет
     * {@code Main} вместе с выбором кодировки; этот конструктор для тестов
     * и для мест, где цвет не нужен.
     */
    public Console(PrintStream out, PrintStream err) {
        this(out, err, false);
    }

    Console(PrintStream out, PrintStream err, boolean colored) {
        this.out = out;
        this.err = err;
        this.colored = colored;
        this.encoder = encoderFor(out);

        boolean unicode = encoder == null;
        this.okMark = unicode ? "✓ " : "[ok] ";
        this.warnMark = unicode ? "⚠ " : "[!] ";
        this.failMark = unicode ? "✗ " : "[x] ";
    }

    /**
     * Кодировщик потока, либо {@code null} — если проверять нечего.
     *
     * <p>Пробный набор нарочно широкий: если кодировка переживает и значки,
     * и типографику, она переживёт всё, что мы печатаем, и перебор
     * по символам можно не включать вовсе.
     */
    private static CharsetEncoder encoderFor(PrintStream stream) {
        try {
            CharsetEncoder probe = stream.charset().newEncoder();
            return probe.canEncode("✓⚠✗—«»…→·") ? null : probe;
        } catch (UnsupportedOperationException e) {
            // Кодировщик есть не у всякой кодировки. Нет — значит заменяем всё,
            // что вне ASCII, по таблице; остальное решит сам PrintStream.
            return null;
        }
    }

    /**
     * Привести строку к тому, что приёмник в состоянии показать.
     *
     * <p>Пакетная видимость — ради теста и ради {@code Main}: справка
     * печатается мимо остальных методов, а закон для неё тот же.
     */
    String adapt(String text) {
        if (encoder == null || text == null) {
            return text;
        }
        StringBuilder rebuilt = null;
        for (int i = 0; i < text.length(); i++) {
            char symbol = text.charAt(i);
            if (encoder.canEncode(symbol)) {
                if (rebuilt != null) {
                    rebuilt.append(symbol);
                }
                continue;
            }
            if (rebuilt == null) {
                rebuilt = new StringBuilder(text.length()).append(text, 0, i);
            }
            rebuilt.append(SUBSTITUTES.getOrDefault(symbol, "?"));
        }
        return rebuilt == null ? text : rebuilt.toString();
    }

    public void print(String text) {
        out.println(adapt(text));
    }

    /** Напечатать как есть, без перевода строки: для многострочной справки. */
    public void block(String text) {
        out.print(adapt(text));
    }

    public void success(String text) {
        out.println(paint(GREEN, okMark) + adapt(text));
    }

    public void warn(String text) {
        out.println(paint(YELLOW, warnMark) + adapt(text));
    }

    public void error(String text) {
        err.println(paint(RED, failMark) + adapt(text));
    }

    public void dim(String text) {
        out.println(paint(DIM, adapt(text)));
    }

    private String paint(String color, String text) {
        return colored ? color + text + RESET : text;
    }
}
