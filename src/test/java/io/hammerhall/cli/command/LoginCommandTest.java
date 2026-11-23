package io.hammerhall.cli.command;

import io.hammerhall.cli.FakePlatform;
import io.hammerhall.cli.Json;
import io.hammerhall.cli.World;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Вход — сквозняком: строка, ввод, настоящий сервер, файл ключей, код возврата.
 * До 0.2.0 вход по паролю был покрыт на 43 %, потому что команду нельзя
 * было запустить целиком (ревью CLI №1, АРХ-2).
 */
@DisplayName("login и logout")
class LoginCommandTest {

    @TempDir
    Path temp;

    private FakePlatform platform;
    private World world;

    @BeforeEach
    void setUp() throws IOException {
        platform = new FakePlatform();
        world = new World(temp).platform(platform);
    }

    @AfterEach
    void tearDown() {
        platform.close();
    }

    @Test
    @DisplayName("почта и пароль → ключ сохранён в ~/.hammerhall/credentials → проверен")
    void exchangesAndSaves() throws IOException {
        platform.on("POST", "/auth/token", json(200, "{\"apiToken\":\"key-a1b2\"}"))
                .on("GET", "/profile", json(200, "[]"));
        world.terminal = true;
        world.lines.add("  ученик@hammerhall.io ");
        world.secrets.add("пароль-с-кириллицей");

        assertThat(world.run("login")).isZero();

        Json.Node body = Json.parse(platform.last("POST", "/auth/token").body());
        assertThat(body.get("email").string()).isEqualTo("ученик@hammerhall.io");
        assertThat(body.get("password").string()).isEqualTo("пароль-с-кириллицей");
        assertThat(Files.readString(world.credentials())).contains(platform.url() + "=key-a1b2");
        assertThat(platform.last("GET", "/profile").header("Authorization")).isEqualTo("Bearer key-a1b2");
        assertThat(world.out()).contains("Почта: ").contains("Ключ получен и сохранён").doesNotContain("НЕ скрывается");
        assertThat(world.prompts).containsExactly("Пароль: ");
    }

    @Test
    @DisplayName("🔴 ВЫС-7: не терминал — ввод не скрыт, и человек об этом предупреждён")
    void warnsWhenVisible() {
        platform.on("POST", "/auth/token", json(200, "{\"apiToken\":\"key\"}")).on("GET", "/profile", json(200, "[]"));
        world.terminal = false;
        world.lines.add("a@b");
        world.secrets.add("p");
        assertThat(world.run("login")).isZero();
        assertThat(world.out()).contains("НЕ скрывается");
    }

    @Test
    @DisplayName("🔴 ВЫС-8: /profile отказал — ключ всё равно СОХРАНЁН, а не потерян; только предупреждение")
    void savesBeforeVerifying() throws IOException {
        platform.on("POST", "/auth/token", json(200, "{\"apiToken\":\"fresh-key\"}"))
                .on("GET", "/profile", json(401, "{}"));
        world.lines.add("a@b");
        world.secrets.add("p");

        assertThat(world.run("login")).isZero();

        assertThat(Files.readString(world.credentials())).contains("fresh-key");
        assertThat(world.out()).contains("Ключ сохранён, но платформа его пока не приняла");
    }

    @Test
    @DisplayName("проверка упала по сети — тоже предупреждение, ключ на месте")
    void verifyNetworkFailure() throws IOException {
        platform.on("POST", "/auth/token", json(200, "{\"apiToken\":\"fresh-key\"}"))
                .on("GET", "/profile", json(500, "{\"message\":\"сбой\\nвторая строка\"}"));
        world.lines.add("a@b");
        world.secrets.add("p");
        assertThat(world.run("login")).isZero();
        assertThat(world.out()).contains("Проверить ключ сейчас не удалось").doesNotContain("вторая строка");
        assertThat(Files.readString(world.credentials())).contains("fresh-key");
    }

    @Test
    @DisplayName("401 на входе — «почта или пароль», а не «перевыпустите ключ»; 403 — «подтвердите почту»")
    void refusals() {
        platform.on("POST", "/auth/token", json(401, "{}"), json(403, "{}"), json(500, "{}"));
        for (String expected : new String[]{"Не подошли почта или пароль", "Почта не подтверждена", "500"}) {
            world.clear();
            world.lines.add("a@b");
            world.secrets.add("p");
            assertThat(world.run("login")).isEqualTo(expected.equals("500") ? 3 : 2);
            assertThat(world.err()).contains(expected).doesNotContain("перевыпустите");
        }
        assertThat(world.credentials()).doesNotExist();
    }

    @Test
    @DisplayName("ответ без ключа или с мусором — не тишина в файл, а ошибка")
    void badKeys() {
        assertThatThrownBy(() -> LoginCommand.extractKey(Json.parse("{\"token\":\"x\"}")))
                .hasMessageContaining("ключа в ответе нет");
        assertThatThrownBy(() -> LoginCommand.extractKey(Json.parse("{\"apiToken\":\"ключ\"}")))
                .hasMessageContaining("неожиданном виде");
        assertThat(LoginCommand.extractKey(Json.parse("{\"apiToken\":\" abc \"}"))).isEqualTo("abc");
    }

    @Test
    @DisplayName("пустая почта, пустой пароль — запрос не уходит")
    void emptyInput() {
        world.lines.add(" ");
        assertThat(world.run("login")).isEqualTo(2);
        assertThat(world.err()).contains("Почта не введена");
        world.lines.add("a@b");
        assertThat(world.run("login")).isEqualTo(2);
        assertThat(world.err()).contains("Пароль не введён");
        assertThat(platform.seen()).isEmpty();
    }

    @Test
    @DisplayName("--token — вставить ключ из кабинета; мусор — понятная ошибка")
    void pastedKey() throws IOException {
        platform.on("GET", "/profile", json(200, "[]"));
        world.secrets.add("  pasted-key-9  ");
        assertThat(world.run("login", "--token")).isZero();
        assertThat(Files.readString(world.credentials())).contains("=pasted-key-9");

        world.secrets.add("ключ кириллицей");
        assertThat(world.run("login", "--token")).isEqualTo(2);
        assertThat(world.err()).contains("Ключ выглядит неправильно");
        world.secrets.add("   ");
        assertThat(world.run("login", "--token")).isEqualTo(2);
        assertThat(world.err()).contains("Пустой ключ");
    }

    @Test
    @DisplayName("ключ аргументом — отказ: он остался бы в истории терминала")
    void keyAsArgumentRejected() {
        assertThat(world.run("login", "abc123")).isEqualTo(2);
        assertThat(world.err()).contains("аргументом не принимается");
    }

    @Test
    @DisplayName("🔑 вход работает и вне кузницы: ключ теперь один на машину (АРХ-3)")
    void outsideForge() throws IOException {
        platform.on("POST", "/auth/token", json(200, "{\"apiToken\":\"k\"}")).on("GET", "/profile", json(200, "[]"));
        world.workDir = Files.createDirectories(world.home.resolve("anywhere"));
        world.lines.add("a@b");
        world.secrets.add("p");
        assertThat(world.run("login")).isZero();
    }

    @Test
    @DisplayName("logout — ключ удалён с машины, и сказано, что на платформе он ещё действует")
    void logout() throws IOException {
        world.loggedIn(platform, "k1");
        assertThat(world.run("logout")).isZero();
        assertThat(world.out()).contains("Ключ удалён").contains("остаётся действующим");
        assertThat(Files.readString(world.credentials())).doesNotContain("k1");

        world.clear();
        assertThat(world.run("logout")).isZero();
        assertThat(world.out()).contains("и так не выполнен");
        assertThat(world.run("logout", "лишнее")).isEqualTo(2);
    }
}
