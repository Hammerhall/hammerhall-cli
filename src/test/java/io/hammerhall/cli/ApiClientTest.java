package io.hammerhall.cli;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

        // ⚠️ Формулировка изменилась 03.08 вместе с переездом ключей.
        // Было: «Токен не принят … перевыпустите в личном кабинете» — совет
        // отправлял человека к кнопке, которой не существует. Стало: причина
        // (отзыв сменой пароля) и единственное действие, которое помогает.
        //
        // 🔑 Этот тест и должен был упасть при такой правке: слова отказа —
        // часть интерфейса продукта, и менять их молча нельзя.
        assertThatThrownBy(() -> client.get("/profile"))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("Ключ не принят")
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
    @DisplayName("🔴 404 с пустым телом — показываем ЧТО спрашивали, а не «(без объяснения)»")
    void explainsEmptyNotFound() throws IOException {
        // Платформа на 404 намеренно молчит: подробность подтверждала бы
        // существование задачи тому, кому её видеть не положено. Но голое
        // «не найдено» не даёт ни одной зацепки. Так в сквозном прогоне
        // 24.07 десять минут искали расхождение имени кузницы.
        ApiClient client = clientFor(404, "");

        assertThatThrownBy(() -> client.get("/tasks/project-stream-forge/MovCode0001"))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("/tasks/project-stream-forge/MovCode0001")
                .hasMessageContaining(".hammerhall/forge")
                .hasMessageNotContaining("без объяснения");
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

    /**
     * Что человек видит, когда ответа нет вовсе.
     *
     * <h3>🔴 Почему эти три случая нельзя было оставлять непроверенными</h3>
     *
     * Все предыдущие тесты описывают платформу, которая ОТВЕТИЛА — пусть
     * отказом, но ответила. Здесь ответа нет: стенд лежит, сеть отвалилась,
     * человек нажал Ctrl-C. Это самые частые события из всех, что случаются
     * с клиентом, и до 03.08 ни одно не было пройдено ни разу.
     *
     * <p>Цена молчания тут высокая: человек, увидевший стектрейс вместо
     * «Платформа недоступна», решит, что сломал что-то сам, и пойдёт искать
     * ошибку в своём решении.
     *
     * <h3>Как это воспроизводится по-настоящему</h3>
     *
     * Без единого мока. Закрытый порт даёт отказ соединения; сокет, который
     * принял и молча закрылся, — обрыв на середине; медленный ответ плюс
     * прерывание потока — третий случай.
     */
    @Nested
    @DisplayName("🔴 когда ответа нет: сеть, обрыв, прерывание")
    @Timeout(30)
    class WhenNetworkFails {

        /** Порт, который точно никто не слушает: заняли и сразу отпустили. */
        private int closedPort() throws IOException {
            try (ServerSocket probe = new ServerSocket(0)) {
                return probe.getLocalPort();
            }
        }

        @Test
        @DisplayName("платформа не отвечает — называем АДРЕС, по которому стучались")
        void explainsConnectionRefused() throws IOException {
            String baseUrl = "http://localhost:" + closedPort();

            assertThatThrownBy(() -> new ApiClient(baseUrl, "test-token-a1b2c3").get("/profile"))
                    .isInstanceOf(CliException.class)
                    .hasMessageContaining("Платформа недоступна")
                    // Адрес в сообщении обязателен: половина таких случаев —
                    // это забытая строка api=http://localhost:8080 в конфиге
                    // после отладки, и увидеть её можно только здесь.
                    .hasMessageContaining(baseUrl);
        }

        @Test
        @DisplayName("соединение оборвалось на середине — «Сбой связи», а не стектрейс")
        void explainsBrokenConnection() throws Exception {
            try (ServerSocket rude = new ServerSocket(0)) {
                // ⚠️ Принимаем в ЦИКЛЕ, а не однократно. HttpClient ПОВТОРЯЕТ
                // идемпотентный запрос, если соединение закрылось до ответа,
                // а прослушивающий сокет принимает подключение в очередь ядра
                // и без нашего accept(). Прими мы один раз — повтор соединился
                // бы успешно и ждал ответа до таймаута в две минуты. Ровно на
                // этом сборка и вставала 03.08.
                Thread accepting = new Thread(() -> {
                    while (!rude.isClosed()) {
                        try (Socket accepted = rude.accept()) {
                            accepted.setSoLinger(true, 0);   // рвём, а не прощаемся
                        } catch (IOException stopped) {
                            return;                          // сокет закрыт — уходим
                        }
                    }
                });
                accepting.setDaemon(true);
                accepting.start();

                String baseUrl = "http://localhost:" + rude.getLocalPort();
                assertThatThrownBy(() -> new ApiClient(baseUrl, "test-token-a1b2c3").get("/profile"))
                        .isInstanceOf(CliException.class)
                        .hasMessageContaining("Сбой связи");
            }
        }

        @Test
        @DisplayName("🔑 прерывание — «Прервано» И восстановленный флаг потока")
        void restoresInterruptFlag() throws Exception {
            CountDownLatch requestReceived = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);

            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                requestReceived.countDown();
                try {
                    // Держим ответ, пока тест не отпустит. Латч, а не sleep:
                    // обработчик не должен пережить тест и мешать stop().
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            });
            server.start();

            ApiClient client = new ApiClient(
                    "http://localhost:" + server.getAddress().getPort(), "test-token-a1b2c3");

            AtomicReference<Throwable> thrown = new AtomicReference<>();
            AtomicBoolean flagRestored = new AtomicBoolean();

            Thread worker = new Thread(() -> {
                try {
                    client.get("/profile");
                } catch (Throwable t) {
                    thrown.set(t);
                    // Флаг читаем ЗДЕСЬ, внутри прерванного потока: снаружи
                    // его уже не увидеть.
                    flagRestored.set(Thread.currentThread().isInterrupted());
                }
            });
            worker.start();

            try {
                assertThat(requestReceived.await(10, TimeUnit.SECONDS))
                        .as("сервер должен был получить запрос до прерывания")
                        .isTrue();
                worker.interrupt();
                worker.join(TimeUnit.SECONDS.toMillis(10));
            } finally {
                release.countDown();
            }

            assertThat(thrown.get())
                    .isInstanceOf(CliException.class)
                    .hasMessageContaining("Прервано");

            // 🔑 Ради этой строки тест и написан. Проглоти клиент прерывание,
            // не восстановив флаг, — вызывающий код (а завтра это может быть
            // пул потоков) не узнал бы, что его просили остановиться.
            // Проверить это иначе, чем изнутри потока, нельзя.
            assertThat(flagRestored)
                    .as("флаг прерывания обязан быть восстановлен")
                    .isTrue();
        }
    }
}
