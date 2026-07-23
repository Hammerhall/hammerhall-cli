package io.hammerhall.cli.command;

import io.hammerhall.cli.CliException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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
}
