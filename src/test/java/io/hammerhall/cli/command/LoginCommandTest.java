package io.hammerhall.cli.command;

import com.sun.net.httpserver.HttpServer;
import io.hammerhall.cli.CliException;
import io.hammerhall.cli.Console;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Вход: обмен почты и пароля на ключ.
 *
 * <h3>Почему настоящий сервер, а не мок</h3>
 *
 * Здесь проверяется работа с HTTP: форма запроса, ОТСУТСТВИЕ заголовка
 * {@code Authorization}, перевод кодов отказа в слова. Мок {@code HttpClient}
 * проверял бы наши представления о нём. {@code com.sun.net.httpserver} встроен
 * в JDK — поднять настоящий сервер дешевле мока и честнее его.
 *
 * <h3>Что здесь охраняется</h3>
 *
 * <ul>
 *   <li><b>Имя поля в ответе.</b> {@code apiToken} — контракт платформы.
 *       Ошибись клиент в имени, {@code Json.string} вернул бы {@code null},
 *       и в конфиг уехала бы пустота. В проекте это уже случалось: профиль
 *       показывал нули при трёх решённых задачах;</li>
 *   <li><b>Анонимность запроса.</b> Ключа на этом шаге нет, и заголовка быть
 *       не должно;</li>
 *   <li><b>Слова отказа.</b> {@code 401} у этой ручки значит не то же самое,
 *       что у остальных. Совет «перевыпустите ключ» человеку, ошибшемуся
 *       паролем, отправляет его чинить исправное.</li>
 * </ul>
 */
@DisplayName("LoginCommand — вход по почте и паролю")
class LoginCommandTest {

    private HttpServer server;

    private final AtomicReference<String> seenAuthorization = new AtomicReference<>();
    private final AtomicReference<String> seenBody = new AtomicReference<>();
    private final AtomicReference<String> seenPath = new AtomicReference<>();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Команда с выводом в никуда: здесь проверяется не печать, а обмен. */
    private LoginCommand command() {
        PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        return new LoginCommand(new Console(sink, sink));
    }

    /** Поднимает сервер, отвечающий заданным кодом и телом на любой путь. */
    private String serverReturning(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            seenAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            seenPath.set(exchange.getRequestURI().getPath());
            seenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, payload.length == 0 ? -1 : payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        return "http://localhost:" + server.getAddress().getPort();
    }

    @Nested
    @DisplayName("обмен почты и пароля на ключ")
    class Exchange {

        @Test
        @DisplayName("успех — ключ берётся из поля apiToken")
        void returnsKeyFromResponse() throws IOException {
            String api = serverReturning(200, "{\"apiToken\":\"a1b2c3d4e5f6\"}");

            String key = command().login(api, "user@example.com", "верный-пароль");

            assertThat(key).isEqualTo("a1b2c3d4e5f6");
            assertThat(seenPath.get()).isEqualTo("/auth/token");
        }

        @Test
        @DisplayName("🔴 запрос уходит БЕЗ заголовка Authorization — ключа ещё нет")
        void sendsNoAuthorizationHeader() throws IOException {
            String api = serverReturning(200, "{\"apiToken\":\"a1b2c3d4e5f6\"}");

            command().login(api, "user@example.com", "пароль");

            assertThat(seenAuthorization.get()).isNull();
        }

        @Test
        @DisplayName("почта и пароль уезжают в теле, пароль — как ввели")
        void sendsCredentialsInBody() throws IOException {
            String api = serverReturning(200, "{\"apiToken\":\"a1b2c3d4e5f6\"}");

            // ⚠️ Пароль по-русски намеренно: он едет в ТЕЛЕ, в UTF-8, и правило
            // «только ASCII» к нему не относится — оно про заголовок. Запрети
            // клиент кириллицу в пароле, он отверг бы пароль, который платформа
            // приняла бы.
            command().login(api, "user@example.com", "пароль с пробелом");

            assertThat(seenBody.get())
                    .contains("\"email\":\"user@example.com\"")
                    .contains("\"password\":\"пароль с пробелом\"");
        }

        @Test
        @DisplayName("пустая почта или пароль — отказ ДО запроса")
        void refusesEmptyCredentials() throws IOException {
            String api = serverReturning(200, "{\"apiToken\":\"a1b2c3d4e5f6\"}");

            assertThatThrownBy(() -> command().login(api, "  ", "пароль"))
                    .isInstanceOf(CliException.class)
                    .hasMessageContaining("Почта не введена");
            assertThatThrownBy(() -> command().login(api, "user@example.com", ""))
                    .isInstanceOf(CliException.class)
                    .hasMessageContaining("Пароль не введён");

            // Положительный контроль к обоим утверждениям: сервер не видел
            // запроса вовсе. Без этой строки «отказ до запроса» было бы
            // истинно и в случае, когда запрос ушёл, а отказ пришёл оттуда.
            assertThat(seenPath.get()).isNull();
        }
    }

    @Nested
    @DisplayName("отказы платформы объясняются словами ЭТОЙ ручки")
    class Refusals {

        @Test
        @DisplayName("🔴 401 — «почта или пароль», а НЕ «перевыпустите ключ»")
        void explainsWrongPassword() throws IOException {
            String api = serverReturning(401, "");

            assertThatThrownBy(() -> command().login(api, "user@example.com", "не тот"))
                    .isInstanceOf(CliException.class)
                    .hasMessageContaining("Не подошли почта или пароль")
                    // Умолчание из ApiClient сюда доехать не должно:
                    // перевыпускать нечего, ключа у человека ещё нет.
                    .hasMessageNotContaining("Перевыпустите");
        }

        @Test
        @DisplayName("403 — почта не подтверждена")
        void explainsUnverifiedEmail() throws IOException {
            String api = serverReturning(403, "");

            assertThatThrownBy(() -> command().login(api, "user@example.com", "пароль"))
                    .isInstanceOf(CliException.class)
                    .hasMessageContaining("Почта не подтверждена");
        }

        @Test
        @DisplayName("429 остаётся умолчанием — своих слов про частоту у команды нет")
        void keepsRateLimitMessage() throws IOException {
            String api = serverReturning(429, "");

            assertThatThrownBy(() -> command().login(api, "user@example.com", "пароль"))
                    .isInstanceOf(CliException.class)
                    .hasMessageContaining("Слишком часто");
        }
    }

    @Nested
    @DisplayName("разбор ответа")
    class Parsing {

        @Test
        @DisplayName("ключ достаётся по имени поля из контракта платформы")
        void readsApiTokenField() {
            assertThat(LoginCommand.extractKey("{\"apiToken\":\"a1b2c3\"}")).isEqualTo("a1b2c3");
        }

        @Test
        @DisplayName("🔴 поля нет — внятный отказ, а не пустой ключ в конфиге")
        void refusesResponseWithoutKey() {
            // Так выглядело бы переименование поля на платформе: Json.string
            // вернёт null, и без этой проверки в конфиг уехала бы пустота.
            assertThatThrownBy(() -> LoginCommand.extractKey("{\"token\":\"a1b2c3\"}"))
                    .isInstanceOf(CliException.class)
                    .hasMessageContaining("ключа в ответе нет");
            assertThatThrownBy(() -> LoginCommand.extractKey("{\"apiToken\":\"\"}"))
                    .isInstanceOf(CliException.class)
                    .hasMessageContaining("ключа в ответе нет");
        }

        @Test
        @DisplayName("ключ с непечатаемым — фраза, а не стектрейс из HttpClient")
        void refusesNonAsciiKey() {
            assertThatThrownBy(() -> LoginCommand.extractKey("{\"apiToken\":\"ключ\"}"))
                    .isInstanceOf(CliException.class)
                    .hasMessageContaining("неожиданном виде");
        }
    }

    @Nested
    @DisplayName("вставленный ключ — что принимаем, что отбиваем")
    class Pasted {

        @Test
        @DisplayName("пробелы по краям обрезаются молча")
        void trimsSurroundingSpaces() {
            // Частый результат копирования из браузера: выделилось с запасом.
            assertThat(LoginCommand.normalize("  a1b2c3d4  ")).isEqualTo("a1b2c3d4");
            assertThat(LoginCommand.normalize("\ta1b2c3d4\n")).isEqualTo("a1b2c3d4");
        }

        @Test
        @DisplayName("обычный ключ проходит как есть")
        void acceptsHexToken() {
            String token = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

            assertThat(LoginCommand.normalize(token)).isEqualTo(token);
        }

        @Test
        @DisplayName("пусто — внятный отказ, а не попытка сохранить пустоту")
        void rejectsBlank() {
            assertThatThrownBy(() -> LoginCommand.normalize(""))
                    .isInstanceOf(CliException.class)
                    .hasMessageContaining("Пустой ключ");
            assertThatThrownBy(() -> LoginCommand.normalize("   "))
                    .isInstanceOf(CliException.class)
                    .hasMessageContaining("Пустой ключ");
            assertThatThrownBy(() -> LoginCommand.normalize(null))
                    .isInstanceOf(CliException.class);
        }

        @Test
        @DisplayName("🔴 кириллица отбивается СЛОВАМИ, а не стектрейсом из HttpClient")
        void rejectsNonAscii() {
            assertThatThrownBy(() -> LoginCommand.normalize("мой-секретный-ключ"))
                    .isInstanceOf(CliException.class)
                    .hasMessageContaining("выглядит неправильно");
        }

        @Test
        @DisplayName("🔴 ключ с пробелом внутри — тоже отказ: заголовок такого не переживёт")
        void rejectsInnerWhitespace() {
            assertThatThrownBy(() -> LoginCommand.normalize("a1b2 c3d4"))
                    .isInstanceOf(CliException.class)
                    .hasMessageContaining("выглядит неправильно");
        }
    }
}
