package io.hammerhall.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Вывод: цвет — только терминалу, непроходимые символы — заменой.
 *
 * <p>Пара «cp866 / UTF-8» — настоящая: русская консоль Windows и всё
 * остальное. С 0.2.0 к ней — cp1251 (старые консоли), где нет блока
 * полоски {@code █}: профиль превращался в вопросы (ревью CLI №1, СР-2).
 */
@DisplayName("Console — кодировка приёмника и цвет")
class ConsoleTest {

    private record Captured(Console console, ByteArrayOutputStream bytesOut, ByteArrayOutputStream bytesErr,
                            Charset charset) {
        String out() {
            return bytesOut.toString(charset);
        }

        String err() {
            return bytesErr.toString(charset);
        }
    }

    private static Captured console(Charset charset, boolean terminal, boolean colored) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Console console = new Console(new PrintStream(out, true, charset), new PrintStream(err, true, charset),
                terminal, colored);
        return new Captured(console, out, err, charset);
    }

    @Test
    @DisplayName("UTF-8: типографика и значки — как есть")
    void utf8KeepsEverything() {
        Captured c = console(StandardCharsets.UTF_8, false, false);
        c.console().success("Готово — «всё» → ок");
        assertThat(c.out()).isEqualTo("✓ Готово — «всё» → ок" + System.lineSeparator());
    }

    @Test
    @DisplayName("cp866: кириллица остаётся, тире и кавычки — ASCII, значок — [ok]")
    void cp866Substitutes() {
        Captured c = console(Charset.forName("IBM866"), false, false);
        c.console().success("Hammerhall — клиент «кузницы»");
        assertThat(c.out()).isEqualTo("[ok] Hammerhall - клиент \"кузницы\"" + System.lineSeparator());
    }

    @Test
    @DisplayName("🔴 СР-2: cp1251 — полоска профиля █ становится #, а не вопросами")
    void cp1251Bar() {
        Captured c = console(Charset.forName("windows-1251"), false, false);
        c.console().print("  SEQ   ████····  4.5");
        assertThat(c.out()).startsWith("  SEQ   ####····  4.5");
        assertThat(c.out()).doesNotContain("?");
        assertThat(Console.PROBE).contains("█");
    }

    @Test
    @DisplayName("🔴 СР-13: эмодзи из лога — один знак замены, а не два вопроса за суррогатные половинки")
    void emojiByCodePoint() {
        Captured c = console(Charset.forName("IBM866"), false, false);
        c.console().print("вывод: 🔥 ▓▒░");
        // ▓▒░ в cp866 есть (псевдографика) — остаются; эмодзи нет — один «?».
        assertThat(c.out()).isEqualTo("вывод: ? ▓▒░" + System.lineSeparator());
        Captured win = console(Charset.forName("windows-1251"), false, false);
        win.console().print("▓▒░");
        assertThat(win.out()).isEqualTo("#+." + System.lineSeparator());
    }

    @Test
    @DisplayName("цвет — только когда велено; без цвета escape-кодов нет")
    void colors() {
        Captured plain = console(StandardCharsets.UTF_8, true, false);
        plain.console().warn("осторожно");
        plain.console().error("плохо");
        plain.console().dim("тихо");
        assertThat(plain.out() + plain.err()).doesNotContain("\u001B[");

        Captured colored = console(StandardCharsets.UTF_8, true, true);
        colored.console().warn("осторожно");
        colored.console().error("плохо");
        assertThat(colored.out()).contains("\u001B[33m");
        assertThat(colored.err()).contains("\u001B[31m");
        assertThat(colored.console().isTerminal()).isTrue();
    }

    @Test
    @DisplayName("приглашение — на той же строке; справка при ошибке — в stderr; предупреждение один раз")
    void promptBlockWarnOnce() {
        Captured c = console(StandardCharsets.UTF_8, false, false);
        c.console().prompt("Почта: ");
        c.console().blockErr("справка\n");
        c.console().block("ещё\n");
        c.console().warnOnce("k", "один раз");
        c.console().warnOnce("k", "один раз");
        c.console().trace("→ GET");
        assertThat(c.out()).startsWith("Почта: ещё").containsOnlyOnce("один раз");
        assertThat(c.err()).contains("справка").contains("→ GET");
    }

    @Test
    @DisplayName("adapt без кодировщика и на null — как есть")
    void adaptPassThrough() {
        Captured c = console(StandardCharsets.UTF_8, false, false);
        assertThat(c.console().adapt(null)).isNull();
        assertThat(c.console().adapt("✓")).isEqualTo("✓");
        c.console().stackTrace(new IllegalStateException("трасса"));
        assertThat(c.err()).contains("IllegalStateException");
    }
}
