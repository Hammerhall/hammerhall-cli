package io.hammerhall.cli;

import io.hammerhall.cli.command.Command;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.hammerhall.cli.FakePlatform.json;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Точка входа: справка, версия, коды возврата. До 0.2.0 {@code Main} был
 * покрыт на 0 % — его нельзя было запустить, не завершив JVM (АРХ-2).
 */
@DisplayName("Main — справка, версия, коды возврата")
class MainTest {

    @TempDir
    Path temp;

    private FakePlatform platform;
    private World world;

    @BeforeEach
    void setUp() throws IOException {
        platform = new FakePlatform();
        world = new World(temp);
    }

    @AfterEach
    void tearDown() {
        platform.close();
    }

    @Test
    @DisplayName("СР-14: help — stdout и 0; без аргументов — stderr и 2; неизвестная команда — 2")
    void help() {
        assertThat(world.run("help")).isZero();
        assertThat(world.out()).contains("hammerhall submit <файл>").contains("Коды возврата");
        assertThat(world.err()).isEmpty();

        world.clear();
        assertThat(world.run()).isEqualTo(2);
        assertThat(world.out()).isEmpty();
        assertThat(world.err()).contains("hammerhall login");

        world.clear();
        assertThat(world.run("--help")).isZero();
        assertThat(world.run("-h")).isZero();
        assertThat(world.run("pull", "--help")).isZero();

        world.clear();
        assertThat(world.run("deploy")).isEqualTo(2);
        assertThat(world.err()).contains("Неизвестная команда: deploy");
        assertThat(world.run("pull", "--wat", "X")).isEqualTo(2);
        assertThat(world.err()).contains("не знает флага --wat");
    }

    @Test
    @DisplayName("БЛОК-2: version и --version — версия, Java, система, адрес платформы")
    void version() {
        world.version = "0.2.0";
        assertThat(world.run("version")).isZero();
        assertThat(world.out()).contains("hammerhall-cli 0.2.0").contains("java ").contains(Platform.DEFAULT);
        world.clear();
        assertThat(world.run("--version")).isZero();
        assertThat(world.out()).contains("hammerhall-cli 0.2.0");
        assertThat(world.run("version", "лишнее")).isEqualTo(2);
    }

    @Test
    @DisplayName("--api со стендом — громкая строка; http:// на чужой хост — отказ с кодом 2")
    void apiFlag() {
        platform.on("GET", "/profile", json(200, "[]"));
        world.run("--api", platform.url(), "version");
        assertThat(world.out()).contains("переопределён");
        assertThat(world.run("version", "--api", "http://api.example.com")).isEqualTo(2);
        assertThat(world.err()).contains("без шифрования");
    }

    @Test
    @DisplayName("🔑 коды: платформа недоступна — 3; отказ — 2")
    void exitCodes() throws IOException {
        world.loggedIn(platform, "k");
        platform.on("GET", "/profile/stream-forge", json(503, "{}"), json(403, "{\"message\":\"нет\"}"));
        assertThat(world.run("profile")).isEqualTo(3);
        assertThat(world.run("profile")).isEqualTo(2);
    }

    @Test
    @DisplayName("ключ, оставшийся в кузнице от 0.1.x, переезжает при первой же команде")
    void migratesOnFirstUse() throws IOException {
        Files.writeString(world.forge.resolve(".hammerhall/config"), "token=old-k\n");
        world.platform(platform);
        // Ключ лежал под боевым адресом — платформу по умолчанию и проверяем.
        world.env.remove("HAMMERHALL_API");
        world.run("logout");
        assertThat(world.forge.resolve(".hammerhall/config")).doesNotExist();
        assertThat(world.out()).contains("перенесён").contains("Ключ удалён");
    }

    @Test
    @DisplayName("непредвиденная ошибка — стектрейс и код 2 (это баг клиента, он нужен целиком)")
    void unexpected() {
        Command broken = new Command() {
            @Override
            public String name() {
                return "broken";
            }

            @Override
            public int run(Args args, Context context) {
                throw new IllegalStateException("так не бывает");
            }
        };
        Args args = Args.parse(new String[]{"broken"});
        int code = Main.execute(args, world.context(args), java.util.Map.of("broken", broken));
        assertThat(code).isEqualTo(2);
        assertThat(world.err()).contains("Непредвиденная ошибка").contains("IllegalStateException")
                .contains("at io.hammerhall");
    }
}
