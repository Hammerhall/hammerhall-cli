package io.hammerhall.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Ключ на диске: запись, чтение и защита от утечки в git.
 *
 * <h3>Почему этот класс нельзя было оставлять без тестов</h3>
 *
 * До 03.08 покрытие {@code Config} было ровно НОЛЬ при 276 инструкциях —
 * самый крупный нуль в клиенте. При этом класс делает единственную вещь,
 * ошибка в которой необратима: кладёт секрет в файл внутри репозитория,
 * который ученик форкает на GitHub.
 *
 * <p>Цена нуля выросла в тот же день. Раньше человек вставлял ключ,
 * полученный где-то ещё, и делал это один раз. Теперь {@code login}
 * выпускает свежий ключ сам и сам кладёт его в файл — то есть путь записи
 * стал проходиться при каждом входе на каждой машине.
 *
 * <h3>🔴 Третья защита проверяется здесь впервые</h3>
 *
 * Javadoc {@code Config} обещает три преграды между ключом и публичным
 * форком: запись в {@code .gitignore} кузницы, права 600 и вопрос самому
 * git — {@code git check-ignore}. Третья была написана, но никогда
 * не проверялась, а именно она сторожит случай, когда первую переписали.
 *
 * <p>⚠️ Тестам этого раздела нужен настоящий {@code git} в {@code PATH},
 * и это осознанно. Клиент живёт внутри клона репозитория — без git его
 * не существует, поэтому требовать git в тестах честнее, чем прятать
 * проверку за {@code assumeTrue} и не выполнять её никогда: своего CI
 * у клиента нет, и «до CI» здесь означает «никогда».
 */
@DisplayName("Config — ключ на диске и защита от утечки")
class ConfigTest {

    /** Вывод команды целиком: и обычный, и предупреждения — в один буфер. */
    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();

    private Console console() {
        PrintStream stream = new PrintStream(captured, true, StandardCharsets.UTF_8);
        return new Console(stream, stream);
    }

    private String output() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    /** Запускает команду в каталоге и возвращает код возврата. */
    private int run(Path directory, String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        process.getInputStream().readAllBytes();
        return process.waitFor();
    }

    @Nested
    @DisplayName("чтение")
    class Loading {

        @Test
        @DisplayName("файла нет — пустой конфиг, а не отказ")
        void missingFileIsEmpty(@TempDir Path root) {
            Config config = Config.load(root);

            assertThat(config.hasToken()).isFalse();
            assertThat(config.api()).isEqualTo("https://api.hammerhall.io");
        }

        @Test
        @DisplayName("ключа нет — сказано, что делать")
        void explainsMissingToken(@TempDir Path root) {
            assertThatThrownBy(() -> Config.load(root).token())
                    .isInstanceOf(CliException.class)
                    .hasMessageContaining("hammerhall login");
        }

        @Test
        @DisplayName("комментарии, пустые строки и мусор без '=' пропускаются")
        void skipsCommentsAndJunk(@TempDir Path root) throws IOException {
            write(root, """
                    # комментарий
                       \s
                    строка без знака равенства
                    token=a1b2c3
                    """);

            assertThat(Config.load(root).token()).isEqualTo("a1b2c3");
        }

        @Test
        @DisplayName("адрес из файла перебивает умолчание, хвостовой слэш срезается")
        void readsApiAndTrimsSlash(@TempDir Path root) throws IOException {
            write(root, "api=http://localhost:8080/\n");

            assertThat(Config.load(root).api()).isEqualTo("http://localhost:8080");
        }
    }

    @Nested
    @DisplayName("запись")
    class Saving {

        @Test
        @DisplayName("🔑 круговой путь: что записали, то и прочли")
        void roundTrip(@TempDir Path root) {
            Config.load(root).saveToken("a1b2c3d4e5f6");

            // Читаем НОВЫМ экземпляром, а не тем же: иначе проверялась бы
            // память процесса, а не файл. Ровно это и ломается молча —
            // формат записи и формат чтения расходятся.
            assertThat(Config.load(root).token()).isEqualTo("a1b2c3d4e5f6");
        }

        @Test
        @DisplayName("повторный вход заменяет ключ и не теряет адрес")
        void keepsOtherValues(@TempDir Path root) throws IOException {
            write(root, "api=http://localhost:8080\ntoken=старый\n");

            Config.load(root).saveToken("новый");

            Config reloaded = Config.load(root);
            assertThat(reloaded.token()).isEqualTo("новый");
            assertThat(reloaded.api()).isEqualTo("http://localhost:8080");
        }

        @Test
        @DisplayName("каталог .hammerhall создаётся, если его не было")
        void createsDirectory(@TempDir Path root) {
            Config.load(root).saveToken("a1b2c3");

            assertThat(root.resolve(".hammerhall/config")).exists();
        }

        @Test
        @DisplayName("права 600 там, где POSIX поддерживается")
        void restrictsPermissions(@TempDir Path root) throws IOException {
            // ⚠️ На Windows этот assumeTrue выключает тест, и разработка идёт
            // именно там. Значит проверка живёт только на Linux — а CI у клиента
            // нет. Записано честно: сегодня её выполняет только тот, кто
            // запустит сборку на Linux вручную.
            assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                    "POSIX-права не поддерживаются этой файловой системой");

            Config.load(root).saveToken("a1b2c3");

            Set<PosixFilePermission> permissions =
                    Files.getPosixFilePermissions(root.resolve(".hammerhall/config"));
            assertThat(permissions).containsExactlyInAnyOrder(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        }
    }

    @Nested
    @DisplayName("🔴 третья защита: спрашиваем сам git")
    class GitIgnoreGuard {

        @Test
        @DisplayName("файл НЕ игнорируется — предупреждаем")
        void warnsWhenNotIgnored(@TempDir Path root) throws IOException, InterruptedException {
            run(root, "git", "init");
            Config config = Config.load(root);
            config.saveToken("a1b2c3");

            config.warnIfTracked(console());

            assertThat(output()).contains("НЕ игнорируется");
        }

        @Test
        @DisplayName("файл игнорируется — молчим (положительный контроль к предыдущему)")
        void silentWhenIgnored(@TempDir Path root) throws IOException, InterruptedException {
            run(root, "git", "init");
            Files.writeString(root.resolve(".gitignore"), ".hammerhall/\n", StandardCharsets.UTF_8);
            Config config = Config.load(root);
            config.saveToken("a1b2c3");

            config.warnIfTracked(console());

            // Без этого теста предупреждение, которое печатается ВСЕГДА,
            // выглядело бы работающим: первая проверка прошла бы и на нём.
            assertThat(output()).doesNotContain("НЕ игнорируется");
        }

        @Test
        @DisplayName("вообще не репозиторий — тоже молчим: git не сторож ключа")
        void silentOutsideRepository(@TempDir Path root) {
            Config config = Config.load(root);
            config.saveToken("a1b2c3");

            config.warnIfTracked(console());

            // git вернёт 128 «not a git repository». Ругаться на это нельзя:
            // отсутствие репозитория не делает ключ уехавшим, а лишнее
            // предупреждение приучает не читать предупреждения.
            assertThat(output()).doesNotContain("НЕ игнорируется");
        }
    }

    private static void write(Path root, String content) throws IOException {
        Path dir = root.resolve(".hammerhall");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("config"), content, StandardCharsets.UTF_8);
    }
}
