package io.hammerhall.cli.command;

import io.hammerhall.cli.CliException;
import io.hammerhall.cli.Console;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Вердикт: что человек видит и с каким кодом выходит команда.
 *
 * <h3>Что охраняется</h3>
 *
 * Код возврата — это интерфейс для скриптов и CI: {@code 0} значит «зачтено».
 * Ошибиться здесь значит сломать чужую автоматизацию молча.
 *
 * <p>И разбор ответа. Сводка по тестам лежит вложенным объектом, а клиент
 * вырезает его строковым поиском — поменяется форма ответа, и человек увидит
 * «0/0 тестов» вместо вердикта, решив, что виновата платформа. Такой код
 * обязан быть под тестом, иначе он ломается тихо.
 *
 * <p>Отдельно: имена упавших тестов печатаются, а сообщения ассертов — нет,
 * и это не упущение. Сервер их и не присылает: в них ожидаемые значения,
 * то есть подсказка к ответу.
 */
@DisplayName("SubmitCommand — вердикт и код возврата")
class SubmitCommandTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private final SubmitCommand command = new SubmitCommand(new Console(
            new PrintStream(out, true, UTF_8), new PrintStream(err, true, UTF_8)));

    private String printed() {
        return (out.toString(UTF_8) + err.toString(UTF_8))
                .replaceAll("\u001B\\[[0-9;]*m", "");
    }

    @Test
    @DisplayName("🔴 PASSED — код 0 и счёт тестов из вложенного объекта")
    void passedExitsZero() {
        int code = command.report("""
                {"status":"PASSED","durationMs":1007,"fromHistory":false,
                 "tests":{"total":10,"passed":10,"failed":0},"failures":[],"log":""}""");

        assertThat(code).isZero();
        assertThat(printed()).contains("PASSED").contains("10/10");
    }

    @Test
    @DisplayName("🔴 FAILED — код 1 и имена упавших тестов")
    void failedListsFailures() {
        int code = command.report("""
                {"status":"FAILED","durationMs":900,"fromHistory":false,
                 "tests":{"total":10,"passed":7,"failed":3},
                 "failures":["фильтрует чётные","сохраняет порядок"],"log":""}""");

        assertThat(code).isEqualTo(1);
        assertThat(printed())
                .contains("7/10")
                .contains("фильтрует чётные")
                .contains("сохраняет порядок");
    }

    @Test
    @DisplayName("вердикт из истории отмечается — иначе мгновенный ответ выглядит сбоем")
    void marksCachedVerdict() {
        // Человек ждал секунду проверки, а получил результат сразу. Без
        // пометки это выглядит так, будто ничего не проверялось.
        command.report("""
                {"status":"PASSED","durationMs":5,"fromHistory":true,
                 "tests":{"total":3,"passed":3,"failed":0},"failures":[],"log":""}""");

        assertThat(printed()).contains("из истории");
    }

    @Test
    @DisplayName("не в стрим-стиле — объясняем причину и показываем лог")
    void styleViolationShowsLog() {
        int code = command.report("""
                {"status":"STYLE_VIOLATION","durationMs":800,"fromHistory":false,
                 "tests":{"total":0,"passed":0,"failed":0},"failures":[],
                 "log":"обнаружен цикл for — задача про стримы"}""");

        assertThat(code).isEqualTo(1);
        assertThat(printed())
                .contains("стрим-стиле")
                .contains("обнаружен цикл for");
    }

    @Test
    @DisplayName("не компилируется — показываем ошибку компилятора")
    void compileErrorShowsLog() {
        int code = command.report("""
                {"status":"COMPILE_ERROR","durationMs":400,"fromHistory":false,
                 "tests":{"total":0,"passed":0,"failed":0},"failures":[],
                 "log":"MovCode0002.java:7: error: ';' expected"}""");

        assertThat(code).isEqualTo(1);
        assertThat(printed())
                .contains("Не компилируется")
                .contains("';' expected");
    }

    @Test
    @DisplayName("таймаут — человеческая фраза, а не слово TIMEOUT")
    void timeoutIsExplained() {
        int code = command.report("""
                {"status":"TIMEOUT","durationMs":45000,"fromHistory":false,
                 "tests":{"total":0,"passed":0,"failed":0},"failures":[],"log":""}""");

        assertThat(code).isEqualTo(1);
        assertThat(printed()).contains("Слишком долго");
    }

    @Test
    @DisplayName("🔴 незнакомый статус — печатаем как есть, не притворяемся, что всё хорошо")
    void unknownStatusIsNotSuccess() {
        // Платформа новее клиента и прислала статус, которого здесь ещё нет.
        // Считать такое зачётом нельзя ни при каких обстоятельствах.
        int code = command.report("""
                {"status":"QUARANTINED","durationMs":10,"fromHistory":false,
                 "tests":{"total":0,"passed":0,"failed":0},"failures":[],
                 "log":"решение отложено на ручную проверку"}""");

        assertThat(code).isEqualTo(1);
        assertThat(printed())
                .contains("QUARANTINED")
                .contains("отложено на ручную проверку");
    }

    @Test
    @DisplayName("🔴 ответ без сводки по тестам — не падаем")
    void survivesMissingTestsBlock() {
        // Форма ответа поменялась или пришёл обрезанный JSON. Вердикт
        // всё равно должен доехать до человека, пусть и без счёта.
        int code = command.report("""
                {"status":"FAILED","durationMs":100,"fromHistory":false,"failures":[]}""");

        assertThat(code).isEqualTo(1);
        assertThat(printed()).contains("FAILED");
    }

    /**
     * Поиск файла решения.
     *
     * <h3>Почему это важнее вердикта</h3>
     *
     * README обещает: «Файл ищется по имени внутри {@code src/main/java},
     * полный путь помнить не нужно». Обещание исполняет {@code locate},
     * и до 03.08 оно не проверялось ничем — 122 инструкции на нуле, самая
     * крупная непокрытая логика клиента. При этом путь проходится при
     * КАЖДОЙ отправке, а ошибиться он может четырьмя способами.
     *
     * <h3>🔑 Отказ «файлов несколько» — не придирчивость</h3>
     *
     * Два файла с одним именем в разных пакетах — это разные задачи. Выбери
     * клиент любой из них сам, человек отправил бы не то решение и получил
     * непонятный вердикт по чужой задаче. Поэтому он отказывается и
     * показывает оба пути.
     */
    @Nested
    @DisplayName("поиск файла решения")
    class Locating {

        /** Кузница: корень с каталогом исходников. */
        private Path forge(Path root, String... relativeFiles) throws IOException {
            for (String relative : relativeFiles) {
                Path file = root.resolve(relative);
                Files.createDirectories(file.getParent());
                Files.writeString(file, "class A {}", UTF_8);
            }
            return root;
        }

        @Test
        @DisplayName("путь как есть — берём его, ничего не ища")
        void findsByDirectPath(@TempDir Path root) throws IOException {
            forge(root, "src/main/java/io/task/MovCode0002.java");
            Path file = root.resolve("src/main/java/io/task/MovCode0002.java");

            assertThat(command.locate(root, file.toString())).isEqualTo(file);
        }

        @Test
        @DisplayName("путь от корня кузницы — тоже без поиска")
        void findsByPathFromRoot(@TempDir Path root) throws IOException {
            forge(root, "src/main/java/io/task/MovCode0002.java");

            Path found = command.locate(root, "src/main/java/io/task/MovCode0002.java");

            assertThat(found).isEqualTo(root.resolve("src/main/java/io/task/MovCode0002.java"));
        }

        @Test
        @DisplayName("🔑 одно имя без расширения — обещание README")
        void findsByBareName(@TempDir Path root) throws IOException {
            forge(root, "src/main/java/io/hammerhall/task/MovCode0002.java");

            Path found = command.locate(root, "MovCode0002");

            assertThat(found).isEqualTo(
                    root.resolve("src/main/java/io/hammerhall/task/MovCode0002.java"));
        }

        @Test
        @DisplayName("имя с расширением — то же самое")
        void findsByNameWithExtension(@TempDir Path root) throws IOException {
            forge(root, "src/main/java/io/hammerhall/task/MovCode0002.java");

            Path found = command.locate(root, "MovCode0002.java");

            assertThat(found).isEqualTo(
                    root.resolve("src/main/java/io/hammerhall/task/MovCode0002.java"));
        }

        @Test
        @DisplayName("🔴 файлов несколько — отказ с перечислением, а не угадывание")
        void refusesWhenAmbiguous(@TempDir Path root) throws IOException {
            forge(root,
                    "src/main/java/io/first/MovCode0002.java",
                    "src/main/java/io/second/MovCode0002.java");

            assertThatThrownBy(() -> command.locate(root, "MovCode0002"))
                    .isInstanceOf(CliException.class)
                    .hasMessageContaining("несколько")
                    // Оба пути обязаны быть в сообщении: без них человек
                    // не знает, какой из них указать целиком.
                    .hasMessageContaining("first")
                    .hasMessageContaining("second")
                    .hasMessageContaining("Укажите путь целиком");
        }

        @Test
        @DisplayName("файла нет — сказано, какое имя искали")
        void refusesWhenMissing(@TempDir Path root) throws IOException {
            forge(root, "src/main/java/io/task/MovCode0001.java");

            assertThatThrownBy(() -> command.locate(root, "MovCode0002"))
                    .isInstanceOf(CliException.class)
                    .hasMessageContaining("MovCode0002.java");
        }

        @Test
        @DisplayName("нет src/main/java — подсказываем, что каталог не тот")
        void refusesOutsideForge(@TempDir Path root) {
            assertThatThrownBy(() -> command.locate(root, "MovCode0002"))
                    .isInstanceOf(CliException.class)
                    .hasMessageContaining("src/main/java");
        }
    }

    /**
     * Идентификатор задачи и чтение файла.
     *
     * <p>Обе функции чистые и обе едут прямо в запрос: первая — в путь
     * {@code /submit/{taskId}}, вторая — в тело.
     */
    @Nested
    @DisplayName("идентификатор задачи и чтение")
    class Reading {

        @Test
        @DisplayName("🔴 идентификатор — имя файла без расширения и БЕЗ пути")
        void takesFileNameOnly(@TempDir Path root) {
            // Уедь сюда путь целиком, платформа ответила бы «задача
            // не найдена» на существующую задачу.
            assertThat(command.taskIdOf(root.resolve("src/main/java/io/t/MovCode0002.java")))
                    .isEqualTo("MovCode0002");
        }

        @Test
        @DisplayName("имя без расширения остаётся как есть")
        void keepsNameWithoutExtension(@TempDir Path root) {
            assertThat(command.taskIdOf(root.resolve("MovCode0002"))).isEqualTo("MovCode0002");
        }

        @Test
        @DisplayName("файл читается как UTF-8 — кириллица в комментарии цела")
        void readsUtf8(@TempDir Path root) throws IOException {
            Path file = root.resolve("MovCode0002.java");
            Files.writeString(file, "// решение задачи\nclass A {}", UTF_8);

            assertThat(command.read(file)).contains("решение задачи");
        }

        @Test
        @DisplayName("🔴 файл больше потолка — отказ до отправки, а не гигабайт в запросе")
        void refusesHugeFile(@TempDir Path root) throws IOException {
            Path file = root.resolve("MovCode0002.java");
            Files.write(file, new byte[256 * 1024 + 1]);

            assertThatThrownBy(() -> command.read(file))
                    .isInstanceOf(CliException.class)
                    .hasMessageContaining("256");
        }

        @Test
        @DisplayName("ровно на потолке — ещё принимается (граница включительно)")
        void acceptsExactlyAtLimit(@TempDir Path root) throws IOException {
            // Положительный контроль к предыдущему: без него отказ,
            // срабатывающий на любом размере, выглядел бы правильным.
            Path file = root.resolve("MovCode0002.java");
            Files.write(file, new byte[256 * 1024]);

            assertThat(command.read(file)).hasSize(256 * 1024);
        }
    }
}
