package io.hammerhall.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Корень кузницы и её идентификатор.
 *
 * <p>Клиент работает внутри кузницы: файлы задач — от её корня, решения
 * ищутся в её {@code src/main/java}. Корень надо найти, откуда бы человек
 * ни запустил команду: из корня, из {@code src/main/java} или из папки
 * с задачей.
 *
 * <h3>🔴 Как ищется (0.2.0)</h3>
 *
 * До 0.2.0 ориентиром была пара «{@code pom.xml} рядом с {@code src/main/java}»,
 * и подъём шёл до корня файловой системы. Пара есть и у модуля
 * многомодульного проекта, и у любого другого Maven-проекта выше по дереву:
 * поиск останавливался не там, и клиент писал файлы задач в чужой проект
 * (ревью CLI №1, СР-10).
 *
 * <p>Теперь ориентир — настоящий маркер кузницы, {@code .hammerhall/forge}.
 * Подъём останавливается на границе репозитория ({@code .git}) и на домашнем
 * каталоге. Корень репозитория без маркера, но с {@code pom.xml} и
 * {@code src/main/java}, — кузница старого образца: идентификатор тогда —
 * имя каталога, и клиент говорит об этом вслух, потому что каталог мог
 * быть переименован при клонировании.
 */
public final class ForgeRoot {

    static final String MARKER = ".hammerhall/forge";

    /**
     * @param root    корень кузницы
     * @param id      идентификатор кузницы
     * @param guessed идентификатор взят из имени каталога, а не из маркера
     */
    public record Found(Path root, String id, boolean guessed) {
    }

    private ForgeRoot() {
    }

    /** Найти кузницу или объяснить, что это не она. */
    public static Found find(Path from, Path home) {
        Found found = search(from, home);
        if (found == null) {
            throw new CliException(Msg.t("forge.notfound"));
        }
        return found;
    }

    /** Найти кузницу; не нашлась — {@code null} (для команд, которым она не обязательна). */
    public static Found search(Path from, Path home) {
        Path stop = home == null ? null : home.toAbsolutePath().normalize();
        Path current = from.toAbsolutePath().normalize();
        while (current != null) {
            Path marker = current.resolve(MARKER);
            if (Files.isRegularFile(marker)) {
                return new Found(current, Ids.forge(readMarker(marker)), false);
            }
            if (Files.exists(current.resolve(".git"))) {
                // Граница репозитория: выше — уже не эта кузница.
                if (Files.isRegularFile(current.resolve("pom.xml"))
                        && Files.isDirectory(current.resolve("src/main/java"))) {
                    String name = current.getFileName().toString();
                    if (!Ids.FORGE.matcher(name).matches()) {
                        throw new CliException(Msg.t("forge.name.bad", name));
                    }
                    return new Found(current, name, true);
                }
                return null;
            }
            if (current.equals(stop)) {
                return null;
            }
            current = current.getParent();
        }
        return null;
    }

    private static String readMarker(Path marker) {
        try {
            String value = Files.readString(marker, StandardCharsets.UTF_8).trim();
            if (value.isEmpty()) {
                throw new CliException(Msg.t("forge.marker.empty", marker));
            }
            return value;
        } catch (IOException e) {
            throw new CliException(Msg.t("forge.marker.unreadable", marker), e);
        }
    }

    /**
     * Путь файла от корня кузницы — для глаз человека, всегда через {@code /}.
     * На Windows {@code relativize} даёт {@code src\main\java\…}; со слэшем
     * путь одинаков в Git Bash, PowerShell и cmd, и его можно вставить обратно
     * в {@code submit}.
     */
    public static String shown(Path root, Path file) {
        return root.relativize(file).toString().replace(java.io.File.separatorChar, '/');
    }
}
