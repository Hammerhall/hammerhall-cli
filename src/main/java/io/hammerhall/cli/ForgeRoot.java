package io.hammerhall.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Поиск корня кузницы.
 *
 * <p>Клиент живёт внутри кузницы и за её пределы не выходит. Значит все
 * пути — от корня репозитория, и его надо найти, откуда бы человек
 * ни запустил команду: из корня, из {@code src/main/java} или из папки
 * с задачей.
 *
 * <p>Ищем вверх по дереву, ориентир — {@code pom.xml} рядом с
 * {@code src/main/java}. Одного {@code pom.xml} мало: в многомодульных
 * проектах он есть на каждом уровне, и можно остановиться не там.
 */
public final class ForgeRoot {

    private ForgeRoot() {
    }

    public static Path find() {
        return find(Path.of("").toAbsolutePath());
    }

    static Path find(Path from) {
        Path current = from;
        while (current != null) {
            if (looksLikeForge(current)) {
                return current;
            }
            current = current.getParent();
        }
        throw new CliException("""
                Это не похоже на кузницу: не найден pom.xml рядом с src/main/java.
                Запустите команду внутри клонированного репозитория кузницы.""");
    }

    private static boolean looksLikeForge(Path dir) {
        return Files.isRegularFile(dir.resolve("pom.xml"))
                && Files.isDirectory(dir.resolve("src/main/java"));
    }

    /**
     * Какой кузнице принадлежит репозиторий.
     * <p>
     * Берём из {@code .hammerhall/forge}, а если файла нет — из имени
     * каталога. Имя каталога человек может переименовать при клонировании,
     * поэтому это запасной путь, а не основной.
     * <p>
     * Живёт здесь, а не в команде: id кузницы нужен и при отправке решения,
     * и при получении задачи. Две копии одного правила разошлись бы при
     * первой же правке.
     */
    public static String forgeId(Path root) {
        Path marker = root.resolve(".hammerhall/forge");
        if (Files.isRegularFile(marker)) {
            try {
                String value = Files.readString(marker, StandardCharsets.UTF_8).trim();
                if (!value.isBlank()) {
                    return value;
                }
            } catch (IOException ignored) {
                // читаем запасной путь ниже
            }
        }
        return root.getFileName().toString();
    }
}
