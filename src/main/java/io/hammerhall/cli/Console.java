package io.hammerhall.cli;

import java.io.PrintStream;

/**
 * Вывод. Отдельным классом, чтобы команды не звали System.out напрямую —
 * иначе их не проверить тестом.
 *
 * <h3>🔴 Цвет — только настоящему терминалу</h3>
 *
 * Escape-коды в файле или конвейере — мусор. Раньше признаком служило
 * {@code System.console() != null}, и это было верно до JDK 22: с этой
 * версии метод возвращает объект ДАЖЕ при перенаправлении, а терминал
 * отличает {@link java.io.Console#isTerminal()}. То есть проверка тихо
 * перестала проверять, и цвет включался всюду — включая консоль Windows,
 * которая escape-коды не разбирает и печатала их как {@code ←[31m}.
 *
 * <p>🔑 Признак теперь приходит СНАРУЖИ: его вычисляет {@code Main} — там же,
 * где по нему выбирается кодировка потока. Один ответ на оба вопроса,
 * и разойтись им негде.
 *
 * <h3>Значки — не в каждой кодировке</h3>
 *
 * {@code ✓ ⚠ ✗} есть в UTF-8, но не в cp866, куда вывод попадает в консоли
 * Windows. Незакодируемый символ превращается в {@code ?}, и человек читает
 * «? Ключ получен». Поэтому набор значков выбирается по кодировке потока,
 * а не назначается раз и навсегда.
 */
public class Console {

    private static final String RESET = "\u001B[0m";
    private static final String RED = "\u001B[31m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String DIM = "\u001B[2m";

    private final PrintStream out;
    private final PrintStream err;
    private final boolean colored;

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

        boolean unicode = canEncode(out, "✓⚠✗");
        this.okMark = unicode ? "✓ " : "[ok] ";
        this.warnMark = unicode ? "⚠ " : "[!] ";
        this.failMark = unicode ? "✗ " : "[x] ";
    }

    /** Переживут ли значки кодировку этого потока. */
    private static boolean canEncode(PrintStream stream, String marks) {
        try {
            return stream.charset().newEncoder().canEncode(marks);
        } catch (UnsupportedOperationException e) {
            // Кодировщик есть не у всякой кодировки. Нет — значит нет.
            return false;
        }
    }

    public void print(String text) {
        out.println(text);
    }

    public void success(String text) {
        out.println(paint(GREEN, okMark) + text);
    }

    public void warn(String text) {
        out.println(paint(YELLOW, warnMark) + text);
    }

    public void error(String text) {
        err.println(paint(RED, failMark) + text);
    }

    public void dim(String text) {
        out.println(paint(DIM, text));
    }

    private String paint(String color, String text) {
        return colored ? color + text + RESET : text;
    }
}
