package io.hammerhall.cli;

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
                Запусти команду внутри клонированного репозитория кузницы.""");
    }

    private static boolean looksLikeForge(Path dir) {
        return Files.isRegularFile(dir.resolve("pom.xml"))
                && Files.isDirectory(dir.resolve("src/main/java"));
    }
}
