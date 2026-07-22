package io.hammerhall.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Конфигурация клиента: токен и адрес платформы.
 *
 * <h3>Где лежит</h3>
 *
 * {@code .hammerhall/config} в корне кузницы — рядом с решениями, а не
 * в домашней папке. Это следствие того, что клиент живёт внутри кузницы:
 * клонировал репозиторий, и всё нужное здесь же.
 *
 * <h3>⚠️ Токен внутри клона — три защиты</h3>
 *
 * Конфиг лежит в репозитории пользователя. Попадёт в {@code git add} —
 * токен уедет в его форк, а форки часто публичные. Поэтому:
 *
 * <ol>
 *   <li>{@code .hammerhall/} прописан в {@code .gitignore} кузницы;</li>
 *   <li>права 600 при создании — на POSIX-системах читает только владелец;</li>
 *   <li>{@link #warnIfTracked} проверяет, что git действительно игнорирует
 *       файл, и предупреждает, если нет.</li>
 * </ol>
 *
 * Третья — паранойя, но дешёвая: {@code .gitignore} можно случайно
 * переписать, а узнать об этом после утечки поздно.
 */
public class Config {

    private static final String DIR = ".hammerhall";
    private static final String FILE = "config";

    private static final String KEY_TOKEN = "token";
    private static final String KEY_API = "api";

    /** Куда ходить, если в конфиге не сказано иное. */
    private static final String DEFAULT_API = "https://api.hammerhall.io";

    private final Path root;
    private final Map<String, String> values;

    private Config(Path root, Map<String, String> values) {
        this.root = root;
        this.values = values;
    }

    /** Загрузить конфиг из корня кузницы. Файла нет — пустой конфиг. */
    public static Config load(Path forgeRoot) {
        Path file = forgeRoot.resolve(DIR).resolve(FILE);
        Map<String, String> values = new HashMap<>();

        if (Files.exists(file)) {
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    int eq = trimmed.indexOf('=');
                    if (eq > 0) {
                        values.put(trimmed.substring(0, eq).trim(),
                                trimmed.substring(eq + 1).trim());
                    }
                }
            } catch (IOException e) {
                throw new CliException("Конфиг не читается: " + file, e);
            }
        }

        return new Config(forgeRoot, values);
    }

    public String token() {
        String token = values.get(KEY_TOKEN);
        if (token == null || token.isBlank()) {
            throw new CliException("""
                    Токен не сохранён. Выполните:
                        hammerhall login""");
        }
        return token;
    }

    public boolean hasToken() {
        String token = values.get(KEY_TOKEN);
        return token != null && !token.isBlank();
    }

    /** Адрес платформы. Переопределяется переменной окружения — удобно при отладке. */
    public String api() {
        String fromEnv = System.getenv("HAMMERHALL_API");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return trimSlash(fromEnv);
        }
        return trimSlash(values.getOrDefault(KEY_API, DEFAULT_API));
    }

    /** Сохранить токен. Файл создаётся с правами 600 там, где это поддерживается. */
    public void saveToken(String token) {
        Path dir = root.resolve(DIR);
        Path file = dir.resolve(FILE);

        try {
            Files.createDirectories(dir);
            values.put(KEY_TOKEN, token);

            StringBuilder text = new StringBuilder("""
                    # Конфигурация клиента Hammerhall.
                    # ⚠️ Здесь токен доступа. Не коммить этот файл.
                    """);
            values.forEach((k, v) -> text.append(k).append('=').append(v).append('\n'));

            Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
            restrictPermissions(file);
        } catch (IOException e) {
            throw new CliException("Не удалось сохранить конфиг: " + file, e);
        }
    }

    /**
     * Права 600: читает только владелец.
     * <p>
     * На Windows POSIX-права не поддерживаются, и это НЕ ошибка — там
     * своя модель доступа. Молча пропускаем: падать из-за того, что
     * система устроена иначе, было бы грубо.
     */
    private void restrictPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows или экзотическая ФС — пропускаем.
        }
    }

    /**
     * Предупредить, если git НЕ игнорирует конфиг.
     * <p>
     * Спрашиваем сам git ({@code git check-ignore}), а не читаем
     * {@code .gitignore} глазами: правила бывают в нескольких файлах,
     * в {@code .git/info/exclude} и в глобальном конфиге. Разбирать это
     * самим — писать половину git.
     */
    public void warnIfTracked(Console console) {
        Path relative = Path.of(DIR, FILE);
        try {
            Process process = new ProcessBuilder(
                    "git", "check-ignore", "-q", relative.toString())
                    .directory(root.toFile())
                    .start();

            int code = process.waitFor();
            // 0 — игнорируется, 1 — НЕ игнорируется, прочее — git не смог
            // (не репозиторий, git не установлен). Ругаться только на 1:
            // отсутствие git не делает токен небезопасным.
            if (code == 1) {
                console.warn("""
                        Файл .hammerhall/config НЕ игнорируется git.
                        Токен может уехать в публичный форк. Добавьте в .gitignore:
                            .hammerhall/""");
            }
        } catch (IOException | InterruptedException ignored) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
            }
            // git недоступен — молчим. Это не повод мешать работе.
        }
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
