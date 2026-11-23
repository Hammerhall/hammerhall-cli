package io.hammerhall.cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Мир для сквозного теста команды: домашний каталог и клон кузницы во
 * временной папке, ввод по сценарию, перехваченный вывод, часы и ожидание,
 * которые не ждут.
 *
 * <p>Команда запускается ровно так, как из {@code Main}: строка
 * разбирается {@link Args}, контекст собирается здесь, код выхода —
 * {@link Main#execute}. Мок нет ни одного: файлы настоящие, сервер
 * ({@link FakePlatform}) настоящий.
 */
public final class World {

    public final Path home;
    public final Path forge;

    public final Map<String, String> env = new HashMap<>();
    public final Deque<String> lines = new ArrayDeque<>();
    public final Deque<String> secrets = new ArrayDeque<>();
    public final List<Duration> sleeps = new ArrayList<>();
    public final List<String> prompts = new ArrayList<>();

    public boolean terminal;
    public Charset charset = StandardCharsets.UTF_8;
    public Path workDir;
    public String version = Version.DEV;
    public Instant now = Instant.parse("2026-11-23T08:00:00Z");

    private ByteArrayOutputStream out = new ByteArrayOutputStream();
    private ByteArrayOutputStream err = new ByteArrayOutputStream();

    /** Пустой дом и кузница {@code stream-forge} с маркером, {@code .git}, pom и исходниками. */
    public World(Path temp) throws IOException {
        this.home = Files.createDirectories(temp.resolve("home"));
        this.forge = Files.createDirectories(home.resolve("work/stream-forge"));
        Files.createDirectories(forge.resolve(".git"));
        Files.createDirectories(forge.resolve("src/main/java"));
        Files.writeString(forge.resolve("pom.xml"), "<project/>");
        Files.createDirectories(forge.resolve(".hammerhall"));
        Files.writeString(forge.resolve(".hammerhall/forge"), "stream-forge\n");
        this.workDir = forge;
    }

    /** Ходить на поддельную платформу (как {@code HAMMERHALL_API}). */
    public World platform(FakePlatform platform) {
        env.put("HAMMERHALL_API", platform.url());
        return this;
    }

    /** Ключ уже сохранён — как после {@code login}. */
    public World loggedIn(FakePlatform platform, String token) throws IOException {
        Path file = home.resolve(".hammerhall/credentials");
        Files.createDirectories(file.getParent());
        Files.writeString(file, platform.url() + "=" + token + "\n");
        return platform(platform);
    }

    /** Файл в исходниках кузницы. */
    public Path source(String relative, String text) throws IOException {
        Path file = forge.resolve("src/main/java").resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return file;
    }

    /** Запустить командную строку; вернуть код выхода. Вывод копится — {@link #out()}, {@link #err()}. */
    public int run(String... raw) {
        Args args = Args.parse(raw);
        return Main.execute(args, context(args));
    }

    /** Контекст — как его собрал бы {@code Main}. */
    public Context context(Args args) {
        Console console = new Console(
                new PrintStream(out, true, charset), new PrintStream(err, true, charset), terminal, false);
        Input input = new Input() {
            @Override
            public String line() {
                return lines.poll();
            }

            @Override
            public char[] secret(String prompt) {
                prompts.add(prompt);
                String typed = secrets.poll();
                return typed == null ? new char[0] : typed.toCharArray();
            }

            @Override
            public boolean hidden() {
                return terminal;
            }
        };
        return new Context(console, input, workDir, home, env,
                Clock.fixed(now, ZoneOffset.UTC), sleeps::add, version,
                args.value("--api"), args.has("--verbose"));
    }

    public Context context() {
        return context(Args.parse(new String[0]));
    }

    public String out() {
        return out.toString(charset);
    }

    public String err() {
        return err.toString(charset);
    }

    /** Весь вывод — и stdout, и stderr. */
    public String all() {
        return out() + err();
    }

    /** Начать вывод с чистого листа. */
    public void clear() {
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
    }

    public Path credentials() {
        return home.resolve(".hammerhall/credentials");
    }
}
