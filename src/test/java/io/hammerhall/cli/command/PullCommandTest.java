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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Первые тесты пакета команд — до 23.07 он был покрыт на ноль.
 *
 * <h3>Что здесь охраняется</h3>
 *
 * {@code pull} — единственная команда клиента, которая <b>пишет</b> в проект
 * человека. Ошибка тут не «неудобно», а «потеряна неделя работы»: не тот
 * путь — файл уедет мимо пакета, нет защиты — затрёт готовое решение,
 * доверие к серверу — запись уйдёт за пределы кузницы.
 *
 * <p>Сеть здесь не нужна: проверяются чистые решения о пути и записи.
 * Разговор с платформой покрыт в {@code ApiClientTest}.
 */
@DisplayName("PullCommand — куда кладём задачу и что не затираем")
class PullCommandTest {

    private static final String SOURCE = """
            package io.hammerhall.streamforge.task.code.level01.easy.part01.ore.mov;

            public class MovCode0002 {
                // package io.чужой.пакет; — это комментарий, а не объявление
            }
            """;

    @Nested
    @DisplayName("идентификатор задачи")
    class TaskId {

        @Test
        @DisplayName("принимаем и с .java, и без")
        void acceptsBothForms() {
            assertThat(PullCommand.taskIdOf("MovCode0002")).isEqualTo("MovCode0002");
            assertThat(PullCommand.taskIdOf("MovCode0002.java")).isEqualTo("MovCode0002");
        }

        @Test
        @DisplayName("🔴 path traversal отвергается до сети и до диска")
        void rejectsTraversal() {
            // Идентификатор уезжает и в URL, и в имя файла. Пропустить такое
            // значит дать записать файл куда угодно.
            assertThatThrownBy(() -> PullCommand.taskIdOf("../../etc/passwd"))
                    .isInstanceOf(CliException.class);
            assertThatThrownBy(() -> PullCommand.taskIdOf("Mov/Code"))
                    .isInstanceOf(CliException.class);
            assertThatThrownBy(() -> PullCommand.taskIdOf(""))
                    .isInstanceOf(CliException.class);
        }
    }

    @Nested
    @DisplayName("пакет из текста задачи")
    class PackageParsing {

        @Test
        @DisplayName("объявление пакета читается")
        void readsPackage() {
            assertThat(PullCommand.packageOf(SOURCE))
                    .isEqualTo("io.hammerhall.streamforge.task.code.level01.easy.part01.ore.mov");
        }

        @Test
        @DisplayName("🔴 слово package в комментарии НИЖЕ объявлением не считается")
        void ignoresPackageWordInComments() {
            // Сканирование останавливается на первом объявлении типа: дальше
            // объявления пакета быть не может, а слово в комментарии — запросто.
            assertThat(PullCommand.packageOf(SOURCE))
                    .doesNotContain("чужой");
        }

        @Test
        @DisplayName("пакета нет — null, а не выдуманное значение")
        void noPackageIsNull() {
            assertThat(PullCommand.packageOf("public class A {}")).isNull();
            assertThat(PullCommand.packageOf("import java.util.List;\npublic class A {}")).isNull();
        }

        @Test
        @DisplayName("сканирование останавливается на ЛЮБОМ объявлении типа")
        void stopsAtEveryDeclarationKeyword() {
            // Четыре слова-ограничителя в коде, а проверялись два. Каждое
            // означает «дальше объявления пакета быть не может»; пропусти
            // клиент одно — он продолжил бы читать тело класса и мог бы
            // принять за пакет слово из строкового литерала.
            assertThat(PullCommand.packageOf("class A {}")).isNull();
            assertThat(PullCommand.packageOf("final class A {}")).isNull();
            assertThat(PullCommand.packageOf("abstract class A {}")).isNull();
        }

        @Test
        @DisplayName("🔑 объявление НИЖЕ ограничителя не подхватывается")
        void ignoresPackageAfterDeclaration() {
            // Положительный контроль к предыдущему тесту: он проверяет, что
            // вернулся null, а этот — что null вернулся ИМЕННО из-за
            // остановки, а не потому что метод не нашёл бы пакет и так.
            assertThat(PullCommand.packageOf("class A {}\npackage io.чужой;")).isNull();
            assertThat(PullCommand.packageOf("package io.свой;\nclass A {}")).isEqualTo("io.свой");
        }
    }

    @Nested
    @DisplayName("путь файла")
    class TargetPath {

        @Test
        @DisplayName("пакет разворачивается в дерево каталогов под src/main/java")
        void buildsPathFromPackage(@TempDir Path root) {
            Path target = PullCommand.targetPath(root, SOURCE, "MovCode0002");

            assertThat(root.relativize(target).toString().replace('\\', '/'))
                    .isEqualTo("src/main/java/io/hammerhall/streamforge/task/code/"
                            + "level01/easy/part01/ore/mov/MovCode0002.java");
        }

        @Test
        @DisplayName("без пакета — корень исходников, а не падение")
        void fallsBackToSourceRoot(@TempDir Path root) {
            Path target = PullCommand.targetPath(root, "public class A {}", "MovCode0002");

            assertThat(root.relativize(target).toString().replace('\\', '/'))
                    .isEqualTo("src/main/java/MovCode0002.java");
        }

        @Test
        @DisplayName("🔴 испорченный пакет с сервера не уводит запись за пределы кузницы")
        void rejectsBrokenPackage(@TempDir Path root) {
            // Слэши и «..» — настоящая попытка выйти из дерева исходников.
            assertThatThrownBy(() -> PullCommand.targetPath(
                    root, "package io/../../etc;\npublic class A {}", "MovCode0002"))
                    .isInstanceOf(CliException.class)
                    .hasMessageContaining("пакет");

            // Пустая часть — тоже поломка. Java при split МОЛЧА отбрасывает
            // пустые хвосты, и без -1 «io...» стал бы невинным «io»: файл
            // уехал бы не в тот каталог, а человек не понял бы почему.
            assertThatThrownBy(() -> PullCommand.targetPath(
                    root, "package io...;\npublic class A {}", "MovCode0002"))
                    .isInstanceOf(CliException.class)
                    .hasMessageContaining("пакет");
        }
    }

    @Nested
    @DisplayName("запись")
    class Writing {

        @Test
        @DisplayName("каталоги пакета создаются, текст пишется в UTF-8")
        void createsDirectoriesAndWrites(@TempDir Path root) throws IOException {
            Path target = root.resolve("src/main/java/io/mov/MovCode0002.java");

            PullCommand.write(target, SOURCE, false);

            assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo(SOURCE);
        }

        @Test
        @DisplayName("🔴 существующий файл НЕ затирается — там может лежать решение")
        void refusesToOverwrite(@TempDir Path root) throws IOException {
            Path target = root.resolve("MovCode0002.java");
            Files.writeString(target, "моё решение, которое я писал неделю");

            assertThatThrownBy(() -> PullCommand.write(target, SOURCE, false))
                    .isInstanceOf(CliException.class)
                    .hasMessageContaining("--force");

            assertThat(Files.readString(target)).startsWith("моё решение");
        }

        @Test
        @DisplayName("--force затирает осознанно")
        void forceOverwrites(@TempDir Path root) throws IOException {
            Path target = root.resolve("MovCode0002.java");
            Files.writeString(target, "старое");

            assertThatCode(() -> PullCommand.write(target, SOURCE, true))
                    .doesNotThrowAnyException();

            assertThat(Files.readString(target)).isEqualTo(SOURCE);
        }
    }

    /**
     * Что человек видит после успешного {@code pull}.
     *
     * <h3>Почему это не «просто печать»</h3>
     *
     * Здесь разбирается ответ платформы: {@code displayName}, {@code stage},
     * {@code leadAxis}. Все три необязательны, и каждое отсутствующее меняет
     * форму строки. Собрать «ступень 3 · ведущая ось Композиция» из того,
     * чего нет, — дело одной забытой проверки: разделитель повиснет
     * в воздухе, а отсутствующее поле напечатается словом {@code null}.
     *
     * <p>🔑 Тот же довод, по которому покрыт {@code ProfileCommand.print}:
     * клиент вырезает поля по именам, платформа их переименовывает
     * молча, и расхождение не даёт ни ошибки, ни отказа — только
     * обеднённый вывод, который никто не заметит.
     */
    @Nested
    @DisplayName("что показываем после того, как забрали")
    class Report {

        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final ByteArrayOutputStream err = new ByteArrayOutputStream();

        private final PullCommand command = new PullCommand(new Console(
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)));

        /** Вывод без управляющих последовательностей цвета. */
        private String printed() {
            return (out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8))
                    .replaceAll("\u001B\\[[0-9;]*m", "");
        }

        private Path path() {
            return Path.of("src/main/java/io/hammerhall/task/MovCode0002.java");
        }

        @Test
        @DisplayName("всё на месте — название, путь, ступень с осью и подсказка")
        void printsEverything() {
            command.report("""
                    {"displayName":"Фильмы одного года","stage":"3",
                     "leadAxis":"Композиция","source":"class A {}"}""",
                    "MovCode0002", path());

            assertThat(printed())
                    .contains("MovCode0002 — Фильмы одного года")
                    .contains("MovCode0002.java")
                    .contains("ступень 3")
                    .contains("ведущая ось Композиция")
                    .contains("·")
                    .contains("hammerhall submit MovCode0002.java");
        }

        @Test
        @DisplayName("🔴 названия нет — печатаем номер, а не слово null")
        void survivesMissingName() {
            command.report("""
                    {"stage":"3","source":"class A {}"}""", "MovCode0002", path());

            assertThat(printed())
                    .contains("MovCode0002")
                    .doesNotContain("null")
                    // Тире появляется только вместе с названием: без этой
                    // проверки строка «MovCode0002 — » выглядела бы успехом.
                    .doesNotContain("MovCode0002 —");
        }

        @Test
        @DisplayName("🔴 только ступень — без разделителя, повисшего в воздухе")
        void printsStageAlone() {
            command.report("""
                    {"displayName":"Фильмы","stage":"3","source":"class A {}"}""",
                    "MovCode0002", path());

            assertThat(printed())
                    .contains("ступень 3")
                    .doesNotContain("·")
                    .doesNotContain("ведущая ось");
        }

        @Test
        @DisplayName("только ведущая ось — тоже без разделителя")
        void printsAxisAlone() {
            command.report("""
                    {"displayName":"Фильмы","leadAxis":"Композиция","source":"class A {}"}""",
                    "MovCode0002", path());

            assertThat(printed())
                    .contains("ведущая ось Композиция")
                    .doesNotContain("·")
                    .doesNotContain("ступень");
        }

        @Test
        @DisplayName("ни ступени, ни оси — строки описания нет вовсе")
        void omitsDescriptionEntirely() {
            command.report("""
                    {"displayName":"Фильмы","source":"class A {}"}""",
                    "MovCode0002", path());

            assertThat(printed())
                    .contains("MovCode0002 — Фильмы")
                    .doesNotContain("ступень")
                    .doesNotContain("ведущая ось")
                    .doesNotContain("·");
        }

        @Test
        @DisplayName("🔑 подсказка про submit есть всегда — это следующий шаг человека")
        void alwaysTellsWhatToDoNext() {
            // Ответ пустой настолько, насколько это возможно: даже тогда
            // человек обязан узнать, чем продолжить.
            command.report("{}", "WldCode0007", path());

            assertThat(printed()).contains("hammerhall submit WldCode0007.java");
        }

        @Test
        @DisplayName("пустые строки в полях считаются отсутствием, а не значением")
        void treatsBlankAsMissing() {
            command.report("""
                    {"displayName":"  ","stage":"","leadAxis":"  ","source":"class A {}"}""",
                    "MovCode0002", path());

            assertThat(printed())
                    .contains("MovCode0002")
                    .doesNotContain("—")
                    .doesNotContain("ступень")
                    .doesNotContain("ведущая ось");
        }
    }
}
