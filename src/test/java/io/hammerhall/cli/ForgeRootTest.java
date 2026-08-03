package io.hammerhall.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

    /**
     * Идентификатор кузницы.
     *
     * <h3>🔴 Почему это стоит проверять пятью тестами при двенадцати строках кода</h3>
     *
     * Значение подставляется в путь КАЖДОГО запроса: {@code /tasks/{forgeId}/…},
     * {@code /submissions/{forgeId}}, {@code /profile/{forgeId}}. Ошибись
     * оно — и платформа честно ответит «задача не найдена» на существующую
     * задачу, а человек будет искать причину у себя в коде.
     *
     * <p>Опасен именно запасной путь. Маркера нет или он пуст — берётся имя
     * каталога, а каталог у ученика называется как получилось при
     * клонировании: {@code stream-forge (1)}, {@code Новая папка}, что угодно.
     * Отказа при этом не будет: запрос уйдёт, просто в несуществующую кузницу.
     *
     * <p>⚠️ Во всех тестах имя каталога намеренно ОТЛИЧАЕТСЯ от содержимого
     * маркера. Совпади они — тест «маркер читается» проходил бы и у кода,
     * который маркер вовсе не открывает.
     */
    @Nested
    @DisplayName("идентификатор кузницы")
    class ForgeIdentifier {

        private Path withMarker(Path root, String content) throws IOException {
            Files.createDirectories(root.resolve(".hammerhall"));
            Files.writeString(root.resolve(".hammerhall/forge"), content);
            return root;
        }

        @Test
        @DisplayName("маркер есть — берём его, а НЕ имя каталога")
        void prefersMarker(@TempDir Path tmp) throws IOException {
            Path root = tmp.resolve("папка-названная-как-попало");
            Files.createDirectories(root);
            withMarker(root, "stream-forge");

            assertThat(ForgeRoot.forgeId(root)).isEqualTo("stream-forge");
        }

        @Test
        @DisplayName("пробелы и перевод строки по краям маркера срезаются")
        void trimsMarker(@TempDir Path tmp) throws IOException {
            Path root = tmp.resolve("папка-названная-как-попало");
            Files.createDirectories(root);
            withMarker(root, "  stream-forge \n");

            // Маркер пишет скрипт публикации, а текстовые файлы почти всегда
            // заканчиваются переводом строки. Не срежь мы его — путь запроса
            // стал бы /tasks/stream-forge%0A/…
            assertThat(ForgeRoot.forgeId(root)).isEqualTo("stream-forge");
        }

        @Test
        @DisplayName("маркера нет — запасной путь: имя каталога")
        void fallsBackToDirectoryName(@TempDir Path tmp) throws IOException {
            Path root = tmp.resolve("stream-forge");
            Files.createDirectories(root);

            assertThat(ForgeRoot.forgeId(root)).isEqualTo("stream-forge");
        }

        @Test
        @DisplayName("🔴 маркер пуст — тоже запасной путь, а не пустой идентификатор")
        void fallsBackWhenMarkerBlank(@TempDir Path tmp) throws IOException {
            Path root = tmp.resolve("stream-forge");
            Files.createDirectories(root);
            withMarker(root, "   \n");

            // Пустой идентификатор дал бы путь /tasks//MovCode0002 — платформа
            // ответила бы 404, и причину искали бы где угодно, только не здесь.
            assertThat(ForgeRoot.forgeId(root)).isEqualTo("stream-forge");
        }

        @Test
        @DisplayName("маркер — каталог, а не файл: запасной путь без падения")
        void survivesMarkerBeingDirectory(@TempDir Path tmp) throws IOException {
            Path root = tmp.resolve("stream-forge");
            Files.createDirectories(root.resolve(".hammerhall/forge"));

            assertThat(ForgeRoot.forgeId(root)).isEqualTo("stream-forge");
        }
    }
}
