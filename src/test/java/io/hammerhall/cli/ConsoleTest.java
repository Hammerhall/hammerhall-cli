package io.hammerhall.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Вывод: цвет и значки.
 *
 * <h3>Почему это стоило тестов именно 03.08</h3>
 *
 * Оба свойства класса оказались сломаны молча, и оба — из-за того, что
 * условие однажды перестало означать то, что означало при написании.
 *
 * <ul>
 *   <li><b>Цвет.</b> Признаком терминала служило {@code System.console() != null}.
 *       С JDK 22 метод возвращает объект и при перенаправлении, поэтому цвет
 *       включался всегда — и в консоли Windows наружу лезло {@code ←[31m};</li>
 *   <li><b>Значки.</b> {@code ✓ ⚠ ✗} назначались раз и навсегда, а в cp866
 *       их нет: человек читал «? Ключ получен».</li>
 * </ul>
 *
 * 🔑 Ни то, ни другое не давало ошибки — только испорченный вывод. Ровно
 * тот класс, который ловится единственным способом: проверить, что видно
 * на выходе, а не что задумано в коде.
 */
@DisplayName("Console — цвет и значки")
class ConsoleTest {

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();

    /** Поток с заданной кодировкой: она и решает судьбу значков. */
    private PrintStream stream(Charset charset) {
        return new PrintStream(captured, true, charset);
    }

    private String printed(Charset charset) {
        return captured.toString(charset);
    }

    @Nested
    @DisplayName("цвет")
    class Colour {

        @Test
        @DisplayName("терминал — escape-коды на месте")
        void paintsForTerminal() {
            PrintStream stream = stream(StandardCharsets.UTF_8);

            new Console(stream, stream, true).success("Ключ получен");

            assertThat(printed(StandardCharsets.UTF_8)).contains("\u001B[32m");
        }

        @Test
        @DisplayName("🔴 не терминал — ни одного escape-кода")
        void staysPlainOtherwise() {
            // Положительный контроль к предыдущему: без него класс, никогда
            // не красящий, прошёл бы одну проверку и провалил бы обе задачи.
            PrintStream stream = stream(StandardCharsets.UTF_8);

            Console console = new Console(stream, stream, false);
            console.success("Ключ получен");
            console.warn("Файл не игнорируется");
            console.error("Ключ не принят");
            console.dim("подробность");

            assertThat(printed(StandardCharsets.UTF_8))
                    .doesNotContain("\u001B[")
                    .contains("Ключ получен");
        }
    }

    @Nested
    @DisplayName("значки")
    class Marks {

        @Test
        @DisplayName("UTF-8 — значки как задуманы")
        void usesGlyphsWhenEncodable() {
            PrintStream stream = stream(StandardCharsets.UTF_8);

            new Console(stream, stream, false).success("Ключ получен");

            assertThat(printed(StandardCharsets.UTF_8)).contains("✓ Ключ получен");
        }

        @Test
        @DisplayName("🔴 cp866 — ASCII вместо вопросительных знаков")
        void fallsBackWhenNotEncodable() {
            // Кодировка консоли русской Windows. Кириллица в ней есть,
            // а ✓ ⚠ ✗ нет — без запасного набора они станут '?'.
            Charset cp866 = Charset.forName("IBM866");
            PrintStream stream = stream(cp866);

            Console console = new Console(stream, stream, false);
            console.success("Ключ получен");
            console.warn("Файл не игнорируется");
            console.error("Ключ не принят");

            String output = printed(cp866);
            assertThat(output)
                    .contains("[ok] Ключ получен")
                    .contains("[!] Файл не игнорируется")
                    .contains("[x] Ключ не принят")
                    // Главное утверждение: в выводе нет потерянных символов.
                    .doesNotContain("?");
        }

        @Test
        @DisplayName("кириллица в cp866 доезжает целой — значки её не портят")
        void keepsCyrillicInCp866() {
            // Положительный контроль к предыдущему: он проверяет отсутствие '?',
            // а это истинно и для пустого вывода.
            Charset cp866 = Charset.forName("IBM866");
            PrintStream stream = stream(cp866);

            new Console(stream, stream, false).print("Решено задач: 10");

            assertThat(printed(cp866)).isEqualTo("Решено задач: 10" + System.lineSeparator());
        }
    }
}
