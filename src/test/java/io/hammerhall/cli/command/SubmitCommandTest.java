package io.hammerhall.cli.command;

import io.hammerhall.cli.FakePlatform;
import io.hammerhall.cli.Json;
import io.hammerhall.cli.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static io.hammerhall.cli.FakePlatform.json;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("submit — решение на проверку")
class SubmitCommandTest {

    private static final String CODE = "package io.x;\n// «кириллица» в комментарии\npublic class MovCode0002 {}\n";

    @TempDir
    Path temp;

    private FakePlatform platform;
    private World world;

    @BeforeEach
    void setUp() throws IOException {
        platform = new FakePlatform();
        world = new World(temp).loggedIn(platform, "k");
        world.source("io/x/MovCode0002.java", CODE);
    }

    @AfterEach
    void tearDown() {
        platform.close();
    }

    @Test
    @DisplayName("PASSED — код 0; тело: кузница, номер, код — с экранированием всех полей")
    void passed() {
        platform.on("POST", "/submit", json(200,
                "{\"status\":\"PASSED\",\"durationMs\":1007,\"tests\":{\"total\":5,\"passed\":5},\"fromHistory\":true}"));
        assertThat(world.run("submit", "MovCode0002.java")).isZero();

        Json.Node body = Json.parse(platform.last("POST", "/submit").body());
        assertThat(body.get("forgeId").string()).isEqualTo("stream-forge");
        assertThat(body.get("taskId").string()).isEqualTo("MovCode0002");
        assertThat(body.get("code").string()).isEqualTo(CODE);
        assertThat(world.out()).contains("PASSED — 5/5 тестов, 1007 мс").contains("из истории");
    }

    @Test
    @DisplayName("🔴 ВЫС-1: ответ с пробелами — «прошло 3 из 5», а не «0/0»; имена упавших тестов")
    void prettyFailed() {
        platform.on("POST", "/submit", json(200, """
                {
                  "status" : "FAILED",
                  "tests" : { "total" : 5, "passed" : 3 },
                  "failures" : [ "sortsByYear", "keepsTies" ]
                }"""));
        assertThat(world.run("submit", "MovCode0002")).isEqualTo(1);
        assertThat(world.err()).contains("FAILED — прошло 3 из 5 тестов");
        assertThat(world.out()).contains("· sortsByYear").contains("· keepsTies");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "CRASHED, сорвалась на нашей стороне",
            "RESOURCE_LIMIT, не уложилось в память",
            "COMPILE_ERROR, не компилируется",
            "STYLE_VIOLATION, не в стрим-стиле",
            "INVALID_INPUT, отклонено до запуска",
            "TIMEOUT, слишком долго",
            "BRAND_NEW, клиент такого не знает"
    })
    @DisplayName("🔴 БЛОК-5: каждый вердикт — словами, включая CRASHED и RESOURCE_LIMIT; код 1")
    void verdicts(String status, String words) {
        platform.on("POST", "/submit", json(200, Json.object("status", status, "log", "строка лога 🔥")));
        assertThat(world.run("submit", "MovCode0002")).isEqualTo(1);
        assertThat(world.err()).contains(words);
        if (!status.equals("TIMEOUT")) {
            assertThat(world.out()).contains("строка лога");
        }
    }

    @Test
    @DisplayName("🔴 БЛОК-5: ответ без статуса — не «Вердикт: null»")
    void noStatus() {
        platform.on("POST", "/submit", json(200, "{}"));
        assertThat(world.run("submit", "MovCode0002")).isEqualTo(1);
        assertThat(world.err()).contains("не назвала вердикт").doesNotContain("null");
    }

    @Nested
    @DisplayName("🔴 БЛОК-1: очередь — 202 и опрос")
    class Queue {

        @Test
        @DisplayName("202 → «в очереди» → «проверяется» → вердикт; шаг — Retry-After сервера")
        void pollsUntilVerdict() {
            platform.on("POST", "/submit", json(202, "{\"submissionId\":\"s-42\",\"status\":\"QUEUED\"}",
                            "Retry-After", "3"))
                    .on("GET", "/submit/s-42",
                            json(202, "{\"status\":\"RUNNING\"}"),
                            json(200, "{\"status\":\"PASSED\",\"tests\":{\"total\":1,\"passed\":1}}"));
            assertThat(world.run("submit", "MovCode0002")).isZero();
            assertThat(world.out()).contains("В очереди").contains("Проверяется").contains("PASSED");
            assertThat(world.sleeps).containsExactly(Duration.ofSeconds(3), Duration.ofSeconds(2));
        }

        @Test
        @DisplayName("очередь не кончается — «решение не потеряно», код 3, а не вечное ожидание")
        void givesUpWithHonestWords() {
            platform.on("POST", "/submit", json(202, "{\"submissionId\":\"s-1\",\"status\":\"QUEUED\"}"))
                    .on("GET", "/submit/s-1", json(202, "{\"status\":\"QUEUED\"}"));
            assertThat(world.run("submit", "MovCode0002")).isEqualTo(3);
            assertThat(world.err()).contains("Решение не потеряно");
            assertThat(world.sleeps.stream().reduce(Duration.ZERO, Duration::plus))
                    .isLessThanOrEqualTo(SubmitCommand.AWAIT_LIMIT);
        }

        @Test
        @DisplayName("202 без номера — понятная ошибка")
        void noId() {
            platform.on("POST", "/submit", json(202, "{\"status\":\"QUEUED\"}"));
            assertThat(world.run("submit", "MovCode0002")).isEqualTo(2);
            assertThat(world.err()).contains("не назвала номер");
        }

        @Test
        @DisplayName("прерывание во время ожидания — «Прервано»")
        void interrupted() {
            platform.on("POST", "/submit", json(202, "{\"submissionId\":\"s-1\",\"status\":\"QUEUED\"}"));
            world.sleeps.clear();
            World w = world;
            io.hammerhall.cli.Args args = io.hammerhall.cli.Args.parse(new String[]{"submit", "MovCode0002"});
            io.hammerhall.cli.Context base = w.context(args);
            io.hammerhall.cli.Context interrupting = new io.hammerhall.cli.Context(base.console(), base.input(),
                    w.workDir, w.home, w.env, base.clock(), d -> { throw new InterruptedException(); },
                    "dev", null, false);
            assertThat(io.hammerhall.cli.Main.execute(args, interrupting)).isEqualTo(2);
            assertThat(Thread.interrupted()).isTrue();
            assertThat(w.err()).contains("Прервано");
        }
    }

    @Test
    @DisplayName("🔴 СР-8: синхронный ответ не дождались — «решение не потеряно», код 3, а не «сбой связи»")
    void slowSyncAnswer() throws Exception {
        com.sun.net.httpserver.HttpServer slow = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("localhost", 0), 0);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        slow.createContext("/", exchange -> {
            try {
                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        slow.start();
        Duration standard = SubmitCommand.syncTimeout;
        SubmitCommand.syncTimeout = Duration.ofMillis(300);
        try {
            String url = "http://localhost:" + slow.getAddress().getPort();
            world.env.put("HAMMERHALL_API", url);
            Files.writeString(world.credentials(), url + "=k\n");
            assertThat(world.run("submit", "MovCode0002")).isEqualTo(3);
            assertThat(world.err()).contains("Решение не потеряно").doesNotContain("Сбой связи");
        } finally {
            SubmitCommand.syncTimeout = standard;
            release.countDown();
            slow.stop(0);
        }
    }

    @Nested
    @DisplayName("файл решения")
    class TheFile {

        @Test
        @DisplayName("🔴 ВЫС-10: файл вне кузницы — не уходит никуда")
        void outsideForge() throws IOException {
            Path secret = world.home.resolve(".ssh/id_rsa");
            Files.createDirectories(secret.getParent());
            Files.writeString(secret, "-----BEGIN PRIVATE KEY-----");
            assertThat(world.run("submit", secret.toString())).isEqualTo(2);
            assertThat(world.err()).contains("Файл вне кузницы");
            assertThat(world.run("submit", "../../.ssh/id_rsa")).isEqualTo(2);
            assertThat(platform.seen()).isEmpty();
        }

        @Test
        @DisabledOnOs(OS.WINDOWS)
        @DisplayName("🔴 ВЫС-10: ссылка из кузницы наружу — тоже вне кузницы")
        void symlinkOut() throws IOException {
            Path outside = Files.writeString(world.home.resolve("Outside.java"), "class Outside {}");
            Files.createSymbolicLink(world.forge.resolve("src/main/java/Outside.java"), outside);
            assertThat(world.run("submit", "Outside.java")).isEqualTo(2);
            assertThat(world.err()).contains("Файл вне кузницы");
        }

        @Test
        @DisplayName("🔴 ВЫС-6: не UTF-8 — понятный совет, а не тихие «?» в коде; BOM срезается")
        void encoding() throws IOException {
            world.source("io/x/MovCode0003.java", "").toFile();
            Files.write(world.forge.resolve("src/main/java/io/x/MovCode0003.java"),
                    "// Привет\nclass MovCode0003 {}".getBytes(Charset.forName("windows-1251")));
            assertThat(world.run("submit", "MovCode0003")).isEqualTo(2);
            assertThat(world.err()).contains("не в UTF-8").contains("Convert");

            platform.on("POST", "/submit", json(200, "{\"status\":\"PASSED\"}"));
            Files.writeString(world.forge.resolve("src/main/java/io/x/MovCode0004.java"), "\uFEFFclass MovCode0004 {}");
            assertThat(world.run("submit", "MovCode0004")).isZero();
            assertThat(Json.parse(platform.last("POST", "/submit").body()).get("code").string())
                    .isEqualTo("class MovCode0004 {}");
        }

        @Test
        @DisplayName("🔴 ВЫС-4: размер проверяется ДО чтения — у файловой системы, а не у прочитанного")
        void sizeBeforeRead() throws IOException {
            Path huge = world.forge.resolve("src/main/java/io/x/MovCode0005.java");
            // На байт больше потолка: этого хватает, чтобы проверить, что размер
            // спрашивается у файловой системы, а не у прочитанного массива.
            // (Гигабайт здесь не нужен — на NTFS он занял бы диск по-настоящему.)
            try (RandomAccessFile file = new RandomAccessFile(huge.toFile(), "rw")) {
                file.setLength(SubmitCommand.MAX_CODE_BYTES + 1L);
            }
            assertThat(world.run("submit", "MovCode0005")).isEqualTo(2);
            assertThat(world.err()).contains("больше 256 КБ");
        }

        @Test
        @DisplayName("🔴 ВЫС-9, СР-5: номер из имени файла проверяется — «копия» не ломает JSON")
        void badFileName() throws IOException {
            world.source("io/x/MovCode0002 (копия).java", "class X {}");
            assertThat(world.run("submit", "MovCode0002 (копия).java")).isEqualTo(2);
            assertThat(world.err()).contains("не похоже на номер задачи");
        }

        @Test
        @DisplayName("поиск: по имени, по пути от корня, несколько одинаковых — список; нет — понятно")
        void locating() throws IOException {
            platform.on("POST", "/submit", json(200, "{\"status\":\"PASSED\"}"));
            assertThat(world.run("submit", "src/main/java/io/x/MovCode0002.java")).isZero();
            world.workDir = world.forge.resolve("src/main/java/io");
            assertThat(world.run("submit", "x/MovCode0002.java")).isZero();
            world.workDir = world.forge;

            world.source("io/y/MovCode0002.java", "class MovCode0002 {}");
            assertThat(world.run("submit", "MovCode0002")).isEqualTo(2);
            assertThat(world.err()).contains("несколько").contains("io/y/MovCode0002.java");

            assertThat(world.run("submit", "Nope0001")).isEqualTo(2);
            assertThat(world.err()).contains("Файл не найден: Nope0001.java");
        }

        @Test
        @DisplayName("нет src/main/java — понятно")
        void noSources() throws IOException {
            try (var walk = Files.walk(world.forge.resolve("src"))) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
            assertThat(world.run("submit", "MovCode0002")).isEqualTo(2);
            assertThat(world.err()).contains("Нет каталога src/main/java");
        }

        @Test
        @DisabledOnOs(OS.WINDOWS)
        @DisplayName("🔴 ВЫС-5: файл не читается — слова, а не стектрейс с кодом 2 «непредвиденная»")
        void unreadable() throws IOException {
            Path file = world.forge.resolve("src/main/java/io/x/MovCode0002.java");
            Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("---------"));
            try {
                org.junit.jupiter.api.Assumptions.assumeFalse(Files.isReadable(file), "под root права не мешают");
                assertThat(world.run("submit", "MovCode0002")).isEqualTo(2);
                assertThat(world.err()).contains("Не удалось прочитать файл").doesNotContain("Непредвиденная");
            } finally {
                Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
            }
        }
    }

    @Test
    @DisplayName("не войдя — «выполните вход», запрос не уходит")
    void notLoggedIn() throws IOException {
        Files.delete(world.credentials());
        assertThat(world.run("submit", "MovCode0002")).isEqualTo(2);
        assertThat(world.err()).contains("hammerhall login");
        assertThat(platform.seen()).isEmpty();
    }
}
