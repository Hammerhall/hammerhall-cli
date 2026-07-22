package io.hammerhall.cli;

import java.io.PrintStream;

/**
 * Вывод. Отдельным классом, чтобы команды не звали System.out напрямую —
 * иначе их не проверить тестом.
 *
 * <p>Цвета через ANSI, но с оговоркой: если вывод перенаправлен в файл
 * или конвейер, {@link System#console()} возвращает null, и escape-коды
 * оказались бы мусором в файле. Тогда печатаем без цвета.
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

    public Console(PrintStream out, PrintStream err) {
        this(out, err, System.console() != null);
    }

    Console(PrintStream out, PrintStream err, boolean colored) {
        this.out = out;
        this.err = err;
        this.colored = colored;
    }

    public void print(String text) {
        out.println(text);
    }

    public void success(String text) {
        out.println(paint(GREEN, "✓ ") + text);
    }

    public void warn(String text) {
        out.println(paint(YELLOW, "⚠ ") + text);
    }

    public void error(String text) {
        err.println(paint(RED, "✗ ") + text);
    }

    public void dim(String text) {
        out.println(paint(DIM, text));
    }

    private String paint(String color, String text) {
        return colored ? color + text + RESET : text;
    }
}
