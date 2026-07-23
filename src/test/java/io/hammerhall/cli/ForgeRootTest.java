package io.hammerhall.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Поиск корня кузницы — от него считаются ВСЕ пути клиента.
 *
 * <p>Человек запускает команду откуда придётся: из корня, из
 * {@code src/main/java}, из папки с задачей. Промах здесь означает либо
 * «файл не найден» на существующем файле, либо конфиг, записанный не туда —
 * и во втором случае токен уедет в чужую папку.
 *
 * <p>Тесты бьют в пакетный {@code find(Path)}, а не в публичный
 * {@code find()}: последний стартует от рабочего каталога процесса, а его
 * в JVM не подменить. Разделение на эти два метода в проде и сделано ради
 * проверяемости.
 */
@DisplayName("ForgeRoot — поиск корня кузницы")
class ForgeRootTest {

    /** Кузница: pom.xml рядом с src/main/java. */
    private static Path forge(Path where) throws IOException {
        Files.createDirectories(where.resolve("src/main/java"));
        Files.writeString(where.resolve("pom.xml"), "<project/>");
        return where;
    }

    @Test
    @DisplayName("из самого корня — он и возвращается")
    void findsFromRoot(@TempDir Path tmp) throws IOException {
        Path forge = forge(tmp.resolve("stream-forge"));

        assertThat(ForgeRoot.find(forge)).isEqualTo(forge);
    }

    @Test
    @DisplayName("из глубокой папки с задачей — поднимаемся до корня")
    void findsFromDeepInside(@TempDir Path tmp) throws IOException {
        Path forge = forge(tmp.resolve("stream-forge"));
        Path taskDir = forge.resolve("src/main/java/io/hammerhall/streamforge/task/code/mov");
        Files.createDirectories(taskDir);

        assertThat(ForgeRoot.find(taskDir)).isEqualTo(forge);
    }

    @Test
    @DisplayName("🔴 одного pom.xml мало: модуль без src/main/java пропускается")
    void skipsModuleWithoutSources(@TempDir Path tmp) throws IOException {
        // Многомодульный проект: pom.xml лежит на каждом уровне. Если бы
        // ориентиром был только он, поиск остановился бы на модуле — и все
        // пути клиента отсчитывались бы от неправильного места.
        Path forge = forge(tmp.resolve("stream-forge"));
        Path module = forge.resolve("tools");
        Files.createDirectories(module);
        Files.writeString(module.resolve("pom.xml"), "<project/>");

        assertThat(ForgeRoot.find(module)).isEqualTo(forge);
    }

    @Test
    @DisplayName("не кузница — внятная ошибка, а не пустой результат")
    void explainsWhenNotAForge(@TempDir Path tmp) throws IOException {
        Path random = tmp.resolve("просто-папка");
        Files.createDirectories(random);

        assertThatThrownBy(() -> ForgeRoot.find(random))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("не похоже на кузницу")
                .hasMessageContaining("pom.xml");
    }
}
