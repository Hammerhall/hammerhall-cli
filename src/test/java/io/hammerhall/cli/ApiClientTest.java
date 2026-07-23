package io.hammerhall.cli;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Разговор с платформой: перевод кодов ответа в человеческие фразы.
 *
 * <h3>Почему настоящий сервер, а не мок</h3>
 *
 * Проверяется работа с HTTP: заголовки запроса, {@code Retry-After} в ответе,
 * тело ошибки. Мок {@code HttpClient} проверял бы наши представления о нём,
 * а не поведение. {@code com.sun.net.httpserver} встроен в JDK — поднять
 * настоящий сервер здесь дешевле мока и честнее его, а правило «в поставке
 * зависимостей нет» не нарушается.
 *
 * <h3>Что охраняется</h3>
 *
 * Формулировки ошибок — часть интерфейса продукта: их читает человек, который
 * просто решает задачу. «HTTP 429» ему не говорит ничего, «повторите через
 * 30 с» говорит всё. Отдельно охраняется чтение {@code Retry-After}: число
 * называет СЕРВЕР, клиент его не выдумывает — поменяется политика пула,
 * и фраза поедет за ней без пересборки клиента.
 */
@DisplayName("ApiClient — коды ответа и Retry-After")
class ApiClientTest {

    private HttpServer server;
    private final AtomicReference<String> seenAuthorization = new AtomicReference<>();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Поднимает сервер, который на любой путь отвечает заданным кодом и телом. */
    private ApiClient clientFor(int status, String body, Map<String, String> headers)
            throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            seenAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getRequestBody().readAllBytes();   // тело надо вычитать

            headers.forEach((name, value) -> exchange.getResponseHeaders().add(name, value));

            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, payload.length == 0 ? -1 : payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        // ⚠️ Токен ТОЛЬКО ASCII: значение HTTP-заголовка обязано быть ASCII,
        // и HttpClient из JDK кириллицу отбивает IllegalArgumentException.
        // Настоящие токены платформы — hex от SHA-256, так что это ещё и
        // ближе к жизни, чем русское слово.
        return new ApiClient("http://localhost:" + server.getAddress().getPort(), "test-token-a1b2c3");
    }

    private ApiClient clientFor(int status, String body) throws IOException {
        return clientFor(status, body, Map.of());
    }

    @Test
    @DisplayName("успех — тело отдаётся как есть, разбор не наш")
    void passesBodyThrough() throws IOException {
        String verdict = """
                {"status":"PASSED","durationMs":1007}""";

        assertThat(clientFor(200, verdict).get("/profile")).isEqualTo(verdict);
    }

    @Test
    @DisplayName("токен уходит в заголовке Bearer")
    void sendsBearerToken() throws IOException {
        clientFor(200, "{}").post("/submit", "{\"code\":\"class A {}\"}");

        assertThat(seenAuthorization.get()).isEqualTo("Bearer test-token-a1b2c3");
    }

    @Test
    @DisplayName("401 — объясняем, что делать, а не печатаем код")
    void explainsUnauthorized() throws IOException {
        ApiClient client = clientFor(401, "{}");

        assertThatThrownBy(() -> client.get("/profile"))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("Токен не принят")
                .hasMessageContaining("hammerhall login");
    }

    @Test
    @DisplayName("404 — показываем message из ответа платформы")
    void showsServerMessage() throws IOException {
        ApiClient client = clientFor(404, """
                {"message":"Задача снята с публикации"}""");

        assertThatThrownBy(() -> client.get("/tasks/stream-forge/MovCode0002"))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("Задача снята с публикации");
    }

    @Test
    @DisplayName("429 — срок повтора берётся у сервера")
    void readsRetryAfterOnRateLimit() throws IOException {
        ApiClient client = clientFor(429, "{}", Map.of("Retry-After", "30"));

        assertThatThrownBy(() -> client.post("/submit", "{}"))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("Слишком часто")
                .hasMessageContaining("30 с");
    }

    @Test
    @DisplayName("🔴 503 — «занята», а не «сломалась», и срок повтора с сервера")
    void readsRetryAfterOnBusy() throws IOException {
        // Платформа отвергла сабмит из-за переполнения пула проверок. Решение
        // НЕ потеряно и НЕ проверено — его надо просто отправить снова.
        // Число называет сервер: раньше здесь была зашитая «минута», и она
        // врала, потому что платформа присылает 5.
        ApiClient client = clientFor(503, "{}", Map.of("Retry-After", "5"));

        assertThatThrownBy(() -> client.post("/submit", "{}"))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("занята")
                .hasMessageContaining("5 с");
    }

    @Test
    @DisplayName("503 без Retry-After — не выдумываем число")
    void survivesBusyWithoutHeader() throws IOException {
        ApiClient client = clientFor(503, "{}");

        assertThatThrownBy(() -> client.post("/submit", "{}"))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("временно недоступна")
                .hasMessageNotContaining(" с.");
    }

    @Test
    @DisplayName("незнакомый код — печатаем как есть, но с телом ответа")
    void reportsUnknownStatus() throws IOException {
        ApiClient client = clientFor(418, """
                {"message":"я чайник"}""");

        assertThatThrownBy(() -> client.get("/profile"))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("418")
                .hasMessageContaining("я чайник");
    }
}
