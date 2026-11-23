package io.hammerhall.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Ключи доступа — в домашнем каталоге, по адресу платформы.
 *
 * <h3>🔑 Почему не в кузнице (0.2.0)</h3>
 *
 * До 0.2.0 ключ лежал в {@code .hammerhall/config} в корне кузницы —
 * в рабочем дереве git. Это решение стоило трёх защит (строка в
 * {@code .gitignore} кузницы, права 600, вызов {@code git check-ignore}
 * с предупреждением), а каталог {@code .hammerhall/} одновременно держал
 * то, что ОБЯЗАНО ехать в git ({@code forge}), и то, что НИКОГДА не должно
 * (ключ). И за каждую кузницу — свой вход: Stream Forge и Spring — два
 * клона, два ключа (ревью CLI №1, АРХ-3).
 *
 * <p>Теперь — как у {@code gh}, {@code npm}, Maven: один файл
 * {@code ~/.hammerhall/credentials}, ключ по адресу платформы. Один вход
 * на машину работает во всех кузницах; три защиты и подпроцесс {@code git}
 * не нужны вместе с проблемой. Решение владельца 23.11.
 *
 * <h3>Переезд</h3>
 *
 * Ключ, оставшийся в кузнице от 0.1.x, переносится сам при первом
 * запуске, а файл из кузницы удаляется — секрету в рабочем дереве git
 * больше незачем лежать. Ключ от своего стенда ({@code api=} в том файле)
 * переезжает под адрес стенда, а не под боевой: перепутать их значило бы
 * отправить ключ стенда на бой.
 *
 * <h3>Формат</h3>
 *
 * Строки {@code адрес=ключ}, порядок — как добавлялись ({@link LinkedHashMap}:
 * до 0.2.0 {@code HashMap} перемешивал строки между сохранениями — СР-14).
 */
public final class Credentials {

    static final String DIR = ".hammerhall";
    static final String FILE = "credentials";

    /** Старый файл в кузнице — только для переезда. */
    static final String LEGACY = ".hammerhall/config";

    private final Path file;
    private final Map<String, String> tokens;

    private Credentials(Path file, Map<String, String> tokens) {
        this.file = file;
        this.tokens = tokens;
    }

    /** Прочитать из домашнего каталога. Файла нет — пусто. */
    public static Credentials load(Path home) {
        Path file = home.resolve(DIR).resolve(FILE);
        return new Credentials(file, readPairs(file));
    }

    public Path file() {
        return file;
    }

    /** Ключ для платформы или {@code null}. */
    public String token(Platform platform) {
        String token = tokens.get(platform.key());
        return token == null || token.isBlank() ? null : token;
    }

    /** Ключ или понятная ошибка «войдите». */
    public String require(Platform platform) {
        String token = token(platform);
        if (token == null) {
            throw new CliException(platform.overridden()
                    ? Msg.t("credentials.none.stand", platform)
                    : Msg.t("credentials.none"));
        }
        return token;
    }

    public void save(Platform platform, String token) {
        tokens.put(platform.key(), token);
        store();
    }

    /** Удалить ключ платформы; {@code false} — его и не было. */
    public boolean remove(Platform platform) {
        if (tokens.remove(platform.key()) == null) {
            return false;
        }
        store();
        return true;
    }

    private void store() {
        StringBuilder text = new StringBuilder(Msg.t("credentials.header"));
        tokens.forEach((k, v) -> text.append(k).append('=').append(v).append('\n'));
        try {
            SafeFiles.writeSecret(file, text.toString());
        } catch (IOException e) {
            throw new CliException(Msg.t("credentials.write", file), e);
        }
    }

    // ---------- переезд из кузницы ----------

    /** Строка {@code api=} из старого файла кузницы — чтобы сказать, что она больше не действует. */
    public static String legacyApi(Path forgeRoot) {
        return forgeRoot == null ? null : readPairs(forgeRoot.resolve(LEGACY)).get("api");
    }

    /**
     * Перенести ключ из кузницы, если он там остался от 0.1.x, и удалить его оттуда.
     *
     * <p>Ключ здесь уже есть — старый из кузницы просто удаляется: второй
     * экземпляр секрета в рабочем дереве git ничем не полезен.
     */
    public void migrateFrom(Path forgeRoot, Console console) {
        if (forgeRoot == null) {
            return;
        }
        Path legacy = forgeRoot.resolve(LEGACY);
        Map<String, String> old = readPairs(legacy);
        String token = old.get("token");
        if (token == null || token.isBlank()) {
            // Ключа нет, но осталась строка api= — она больше не действует
            // (и о ней уже предупредили): убрать, чтобы не предупреждать вечно.
            if (old.containsKey("api")) {
                clean(legacy, old, console);
            }
            return;
        }

        Platform target;
        String api = old.get("api");
        try {
            target = api == null || api.isBlank() || Platform.isDefault(api)
                    ? Platform.standard()
                    : new Platform(Platform.validate(api.trim()), true);
        } catch (CliException badAddress) {
            console.warn(Msg.t("credentials.migrate.badapi", legacy));
            return;
        }

        boolean already = token(target) != null;
        if (!already) {
            tokens.put(target.key(), token.trim());
            store();
        }

        if (clean(legacy, old, console)) {
            console.dim(already
                    ? Msg.t("credentials.migrate.dropped", legacy)
                    : Msg.t("credentials.migrate.moved", file));
        }
    }

    /**
     * Убрать из старого файла то, что писал туда клиент 0.1.x ({@code token},
     * {@code api}); остальное — не наше, оставить. Не вышло — сказать.
     */
    private static boolean clean(Path legacy, Map<String, String> old, Console console) {
        Set<String> written = Set.of("token", "api");
        try {
            if (written.containsAll(old.keySet())) {
                Files.delete(legacy);
            } else {
                StringBuilder rest = new StringBuilder();
                old.forEach((k, v) -> {
                    if (!written.contains(k)) {
                        rest.append(k).append('=').append(v).append('\n');
                    }
                });
                SafeFiles.write(legacy, rest.toString());
            }
            return true;
        } catch (IOException e) {
            console.warn(Msg.t("credentials.migrate.leftover", legacy));
            return false;
        }
    }

    private static Map<String, String> readPairs(Path file) {
        Map<String, String> values = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return values;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                // Ключ строки — адрес: двоеточие в нём есть, знака равенства нет.
                int eq = trimmed.indexOf('=');
                if (eq > 0) {
                    values.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
                }
            }
        } catch (IOException e) {
            throw new CliException(Msg.t("credentials.read", file), e);
        }
        return values;
    }
}
