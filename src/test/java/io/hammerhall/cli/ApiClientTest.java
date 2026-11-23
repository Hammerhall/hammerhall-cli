package io.hammerhall.cli;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static io.hammerhall.cli.FakePlatform.json;
import static io.hammerhall.cli.FakePlatform.raw;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Разговор с платформой: коды ответа — в слова, {@code Retry-After} —
 * в срок, версия — в заголовок.
 *
 * <h3>Почему настоящий сервер, а не мок</h3>
 *
 * Проверяется работа с HTTP: заголовки запроса, перенаправления,
 * {@code Retry-After}, тела ошибок. Мок {@code HttpClient} проверял бы наши
 * представления о нём, а не поведение. {@code com.sun.net.httpserver}
 * встроен в JDK — честнее мока, и правило «без зависимостей» цело.
 *
 * <p>Формулировки ошибок — часть интерфейса продукта: их читает человек,
 * который просто решает задачу. Менять их молча нельзя — тест упадёт.
 */
@DisplayName("ApiClient — коды ответа, Retry-After, версия")
class ApiClientTest {

    @TempDir
    Path temp;

    private FakePlatform platform;
    private World world;

    @BeforeEach
    void setUp() throws IOException {
        platform = new FakePlatform();
        world = new World(temp).platform(platform);
        world.version = "0.2.0";
    }

    @AfterEach
    void tearDown() {
        platform.close();
    }

    private ApiClient client() {
        return world.context().withToken("test-token-a1b2c3");
    }

    @Test
    @DisplayName("успех — тело разобрано; ключ — в Bearer; версия — в User-Agent (БЛОК-2)")
    void success() {
        platform.on("GET", "/profile", json(200, "{\"solvedTasks\":3}"));
        try (ApiClient api = client()) {
            assertThat(api.get("profile").body().get("solvedTasks").asLong(0)).isEqualTo(3);
        }
        FakePlatform.Seen seen = platform.last("GET", "/profile");
        assertThat(seen.header("Authorization")).isEqualTo("Bearer test-token-a1b2c3");
        assertThat(seen.header("User-Agent")).startsWith("hammerhall-cli/0.2.0 (java ");
    }

    @Test
    @DisplayName("без ключа — заголовка Authorization нет вовсе; пустой ответ 204 — MISSING")
    void anonymous() {
        platform.on("POST", "/auth/token", raw(204, null, ""));
        try (ApiClient api = world.context().anonymous()) {
            assertThat(api.post("{}", ApiClient.DEFAULT_TIMEOUT, "auth", "token").body().isMissing()).isTrue();
        }
        assertThat(platform.last("POST", "/auth/token").header("Authorization")).isNull();
    }

    @Test
    @DisplayName("🔴 ВЫС-9: сегменты пути кодируются — пробел не даёт стектрейс")
    void encodedSegments() {
        platform.on("GET", "/tasks/a%20b/X", json(200, "{}"));
        try (ApiClient api = client()) {
            api.get("tasks", "a b", "X");
        }
        assertThat(platform.last("GET", "/tasks/a%20b/X")).isNotNull();
    }

    @Test
    @DisplayName("🔴 БЛОК-3: перенаправление не выполняется — ключ за ним не уходит")
    void noRedirects() {
        platform.on("GET", "/profile", raw(302, null, "", "Location", "http://evil.example/profile"));
        try (ApiClient api = client()) {
            assertThatThrownBy(() -> api.get("profile"))
                    .isInstanceOf(CliException.class)
                    .hasMessageContaining("перенаправлением")
                    .hasMessageContaining("evil.example");
        }
    }

    @Test
    @DisplayName("🔴 БЛОК-2: сервер ждёт клиента новее — предупреждение, один раз за запуск")
    void minimumVersion() {
        platform.on("GET", "/profile", json(200, "{}", ApiClient.MIN_CLIENT_HEADER, "0.3.0"));
        try (ApiClient api = client()) {
            api.get("profile");
            api.get("profile");
        }
        assertThat(world.out()).containsOnlyOnce("Клиент устарел: у вас 0.2.0, платформа ждёт 0.3.0");
    }

    @Test
    @DisplayName("426 — клиент старше допустимого: слова сервера и что делать")
    void upgradeRequired() {
        platform.on("GET", "/profile", json(426, "{\"message\":\"нужна 0.3.0\"}"));
        try (ApiClient api = client()) {
            assertThatThrownBy(() -> api.get("profile"))
                    .hasMessageContaining("нужна 0.3.0").hasMessageContaining("Обновите кузницу");
        }
    }

    @Test
    @DisplayName("успех, но не JSON — понятная ошибка, а не трасса")
    void successNotJson() {
        platform.on("GET", "/profile", raw(200, "text/html", "<html>прокси</html>"));
        try (ApiClient api = client()) {
            assertThatThrownBy(() -> api.get("profile")).hasMessageContaining("не JSON");
        }
    }

    @Nested
    @DisplayName("отказы — человеческими словами")
    class Refusals {

        private CliException refusal(FakePlatform.Reply reply) {
            platform.on("GET", "/tasks/stream-forge/MovCode0002", reply);
            try (ApiClient api = client()) {
                api.get("tasks", "stream-forge", "MovCode0002");
            } catch (CliException e) {
                return e;
            }
            throw new AssertionError("отказа не было");
        }

        @Test
        @DisplayName("401 — что делать, а не код")
        void unauthorized() {
            assertThat(refusal(json(401, "{}")).getMessage()).contains("Ключ не принят").contains("hammerhall login");
        }

        @Test
        @DisplayName("403 и 400 — слова сервера")
        void forbiddenAndBad() {
            assertThat(refusal(json(403, "{\"message\":\"Примите новую редакцию\"}")).getMessage())
                    .contains("Доступ закрыт").contains("Примите новую редакцию");
            assertThat(refusal(json(400, "{\"message\":\"нет поля\"}")).getMessage()).contains("нет поля");
            assertThat(refusal(json(400, "{}")).getMessage()).contains("без объяснения");
        }

        @Test
        @DisplayName("404 с телом — слова сервера; пустой — ЧТО спрашивали")
        void notFound() {
            assertThat(refusal(json(404, "{\"message\":\"Задача снята с публикации\"}")).getMessage())
                    .contains("Задача снята с публикации");
            assertThat(refusal(raw(404, null, "")).getMessage())
                    .contains("/tasks/stream-forge/MovCode0002").contains(".hammerhall/forge");
        }

        @Test
        @DisplayName("413")
        void tooLarge() {
            assertThat(refusal(json(413, "{}")).getMessage()).contains("слишком большой");
        }

        @Test
        @DisplayName("🔑 429 квоты — текст сервера как есть: в нём уже сказано «когда»")
        void quota() {
            CliException e = refusal(json(429,
                    "{\"message\":\"Сегодня взято 4 новых задач из 4 — следующие завтра.\",\"reason\":\"day\"}",
                    "Retry-After", "43200"));
            assertThat(e.getMessage()).isEqualTo("Сегодня взято 4 новых задач из 4 — следующие завтра.");
            assertThat(e.status()).isEqualTo(429);
            assertThat(world.sleeps).as("12 часов клиент не выжидает").isEmpty();
        }

        @Test
        @DisplayName("429 без текста — срок с сервера, по-человечески; без срока — без числа")
        void tooManyWithoutText() {
            assertThat(refusal(json(429, "{}", "Retry-After", "600")).getMessage())
                    .contains("Слишком часто").contains("10 мин");
            assertThat(refusal(json(429, "{}")).getMessage()).contains("Подождите немного");
        }

        @Test
        @DisplayName("🔴 503 — «занята», срок с сервера; ВЫС-11: дата — не печатается как есть")
        void busy() {
            CliException e = refusal(json(503, "{}", "Retry-After", "Mon, 23 Nov 2026 08:10:00 GMT"));
            assertThat(e.getMessage()).contains("занята").contains("10 мин").doesNotContain("GMT");
            assertThat(e.kind()).isEqualTo(CliException.Kind.UNAVAILABLE);
            assertThat(refusal(json(503, "{}")).getMessage()).contains("временно недоступна");
        }

        @Test
        @DisplayName("🔴 СР-9: HTML-страница 502 не выливается в консоль; длинный JSON — обрезан")
        void bodies() {
            CliException gateway = refusal(raw(502, "text/html", "<html><body>Bad Gateway</body></html>"));
            assertThat(gateway.getMessage()).contains("502").doesNotContain("<html>");
            assertThat(gateway.kind()).isEqualTo(CliException.Kind.UNAVAILABLE);

            String longBody = "{\"trace\":\"" + "x".repeat(1000) + "\"}";
            CliException teapot = refusal(json(418, longBody));
            assertThat(teapot.getMessage()).contains("418").contains("…");
            assertThat(teapot.getMessage().length()).isLessThan(400);

            assertThat(refusal(raw(500, "application/json", "{не json")).getMessage()).contains("без объяснения");
            assertThat(refusal(json(500, "{\"message\":\"упало\"}")).kind()).isEqualTo(CliException.Kind.UNAVAILABLE);
            assertThat(refusal(json(504, "{}")).getMessage()).contains("504");
        }
    }

    @Nested
    @DisplayName("СР-7: короткий Retry-After клиент выжидает сам")
    class AutoRetry {

        @Test
        @DisplayName("503 с 5 с — подождать и повторить; строка «повторю через 5 с»")
        void retriesBusy() {
            platform.on("POST", "/submit",
                    json(503, "{}", "Retry-After", "5"),
                    json(200, "{\"status\":\"PASSED\"}"));
            try (ApiClient api = client()) {
                assertThat(api.post("{}", ApiClient.DEFAULT_TIMEOUT, "submit").body().get("status").string())
                        .isEqualTo("PASSED");
            }
            assertThat(world.sleeps).containsExactly(Duration.ofSeconds(5));
            assertThat(world.out()).contains("повторю через 5 с");
        }

        @Test
        @DisplayName("не больше двух повторов; Retry-After: 0 — пауза в секунду, а не долбёжка")
        void givesUp() {
            platform.on("GET", "/profile", json(429, "{}", "Retry-After", "0"));
            try (ApiClient api = client()) {
                assertThatThrownBy(() -> api.get("profile")).hasMessageContaining("Слишком часто");
            }
            assertThat(world.sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(1));
        }

        @Test
        @DisplayName("прерывание во время паузы — «Прервано» и восстановленный флаг")
        void interruptedPause() {
            platform.on("GET", "/profile", json(503, "{}", "Retry-After", "3"));
            Context interrupting = new Context(world.context().console(), world.context().input(),
                    world.workDir, world.home, world.env, world.context().clock(),
                    d -> { throw new InterruptedException(); }, "0.2.0", null, false);
            try (ApiClient api = interrupting.withToken("t")) {
                assertThatThrownBy(() -> api.get("profile")).hasMessageContaining("Прервано");
                assertThat(Thread.interrupted()).isTrue();   // и сбрасываем, чтобы не мешать соседям
            }
        }
    }

    @Test
    @DisplayName("--verbose — запрос и ответ в stderr, вывод команды чистый")
    void verbose() {
        platform.on("GET", "/profile", json(200, "{}"));
        Args args = Args.parse(new String[]{"--verbose"});
        try (ApiClient api = world.context(args).withToken("t")) {
            api.get("profile");
        }
        assertThat(world.err()).contains("→ GET " + platform.url() + "/profile").contains("← 200");
    }

    /**
     * Что человек видит, когда ответа нет вовсе: стенд лежит, сеть
     * отвалилась, человек нажал Ctrl-C. Самые частые события из всех.
     * Без единого мока: закрытый порт, сокет, который рвёт соединение,
     * и медленный ответ плюс прерывание.
     */
    @Nested
    @DisplayName("🔴 когда ответа нет: сеть, обрыв, таймаут, прерывание")
    @Timeout(60)
    class WhenNetworkFails {

        private int closedPort() throws IOException {
            try (ServerSocket probe = new ServerSocket(0)) {
                return probe.getLocalPort();
            }
        }

        private Context at(String url) {
            world.env.put("HAMMERHALL_API", url);
            return world.context();
        }

        @Test
        @DisplayName("платформа не отвечает — называем АДРЕС; код возврата 3")
        void connectionRefused() throws IOException {
            String url = "http://localhost:" + closedPort();
            try (ApiClient api = at(url).withToken("t")) {
                assertThatThrownBy(() -> api.get("profile"))
                        .isInstanceOf(CliException.class)
                        .hasMessageContaining("Платформа недоступна")
                        .hasMessageContaining(url)
                        .extracting(e -> ((CliException) e).kind())
                        .isEqualTo(CliException.Kind.UNAVAILABLE);
            }
        }

        @Test
        @DisplayName("соединение оборвалось на середине — «Сбой связи», а не стектрейс")
        void brokenConnection() throws Exception {
            try (ServerSocket rude = new ServerSocket(0)) {
                // ⚠️ Принимаем в ЦИКЛЕ: HttpClient повторяет идемпотентный запрос,
                // если соединение закрылось до ответа, а прослушивающий сокет
                // принимает подключение в очередь ядра и без accept(). Прими мы
                // один раз — повтор ждал бы ответа до таймаута.
                Thread accepting = new Thread(() -> {
                    while (!rude.isClosed()) {
                        try (Socket accepted = rude.accept()) {
                            accepted.setSoLinger(true, 0);
                        } catch (IOException stopped) {
                            return;
                        }
                    }
                });
                accepting.setDaemon(true);
                accepting.start();
                try (ApiClient api = at("http://localhost:" + rude.getLocalPort()).withToken("t")) {
                    // Разрыв JDK называет по-разному — «сбросом» на середине или
                    // отказом при повторном подключении. Человеку важно одно:
                    // слова, а не трасса, и код 3 «платформа недоступна».
                    assertThatThrownBy(() -> api.get("profile"))
                            .isInstanceOf(CliException.class)
                            .satisfies(e -> assertThat(e.getMessage()).containsAnyOf("Сбой связи", "Платформа недоступна"))
                            .extracting(e -> ((CliException) e).kind())
                            .isEqualTo(CliException.Kind.UNAVAILABLE);
                }
            }
        }

        @Test
        @DisplayName("🔴 СР-8: ответ не пришёл вовремя — вид TIMEOUT, а не «сбой связи»")
        void responseTimeout() throws Exception {
            CountDownLatch release = new CountDownLatch(1);
            HttpServer slow = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            slow.createContext("/", exchange -> {
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            });
            slow.start();
            try (ApiClient api = at("http://localhost:" + slow.getAddress().getPort()).withToken("t")) {
                assertThatThrownBy(() -> api.send("GET", null, Duration.ofMillis(300), "profile"))
                        .isInstanceOf(CliException.class)
                        .hasMessageContaining("не ответила")
                        .extracting(e -> ((CliException) e).kind())
                        .isEqualTo(CliException.Kind.TIMEOUT);
            } finally {
                release.countDown();
                slow.stop(0);
            }
        }

        @Test
        @DisplayName("🔑 прерывание — «Прервано» И восстановленный флаг потока")
        void restoresInterruptFlag() throws Exception {
            CountDownLatch received = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            HttpServer hold = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            hold.createContext("/", exchange -> {
                received.countDown();
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            });
            hold.start();
            ApiClient api = at("http://localhost:" + hold.getAddress().getPort()).withToken("t");

            AtomicReference<Throwable> thrown = new AtomicReference<>();
            AtomicBoolean flagRestored = new AtomicBoolean();
            Thread worker = new Thread(() -> {
                try {
                    api.get("profile");
                } catch (Throwable t) {
                    thrown.set(t);
                    // Флаг читаем ЗДЕСЬ, внутри прерванного потока: снаружи его уже не увидеть.
                    flagRestored.set(Thread.currentThread().isInterrupted());
                }
            });
            worker.start();
            try {
                assertThat(received.await(10, TimeUnit.SECONDS)).isTrue();
                worker.interrupt();
                worker.join(TimeUnit.SECONDS.toMillis(10));
            } finally {
                release.countDown();
                hold.stop(0);
                api.close();
            }
            assertThat(thrown.get()).isInstanceOf(CliException.class).hasMessageContaining("Прервано");
            assertThat(flagRestored).as("флаг прерывания обязан быть восстановлен").isTrue();
        }
    }

    @Test
    @DisplayName("база с путём — сегменты добавляются к нему")
    void baseWithPath() {
        platform.on("GET", "/api/profile", json(200, "{}"));
        Context ctx = new Context(world.context().console(), world.context().input(), world.workDir, world.home,
                java.util.Map.of(), world.context().clock(), world.sleeps::add, "0.2.0",
                platform.url() + "/api", false);
        try (ApiClient api = ctx.withToken("t")) {
            api.get("profile");
        }
        assertThat(platform.last("GET", "/api/profile")).isNotNull();
        assertThat(URI.create(platform.url()).getHost()).isEqualTo("localhost");
    }
}
