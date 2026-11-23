package io.hammerhall.cli.command;

import io.hammerhall.cli.FakePlatform;
import io.hammerhall.cli.Json;
import io.hammerhall.cli.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.hammerhall.cli.FakePlatform.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("pull — задача из кузницы в проект")
class PullCommandTest {

    private static final String SOURCE = """
            package io.hammerhall.forge.stream.movie;

            /** Задача. Слово package в комментарии ниже не путает разбор. */
            public class MovCode0002 {
            }
            """;

    @TempDir
    Path temp;

    private FakePlatform platform;
    private World world;

    @BeforeEach
    void setUp() throws IOException {
        platform = new FakePlatform();
        world = new World(temp).loggedIn(platform, "k");
    }

    @AfterEach
    void tearDown() {
        platform.close();
    }

    private String card(String id) {
        return Json.object("id", id, "source", SOURCE, "displayName", "Первый фильм", "stage", "Easy", "leadAxis", "SEQ");
    }

    private Path target() {
        return world.forge.resolve("src/main/java/io/hammerhall/forge/stream/movie/MovCode0002.java");
    }

    @Test
    @DisplayName("кладёт файл в пакет из текста задачи и говорит, что дальше")
    void pulls() throws IOException {
        platform.on("GET", "/tasks/stream-forge/MovCode0002", json(200, card("MovCode0002")));
        assertThat(world.run("pull", "MovCode0002.java")).isZero();
        assertThat(Files.readString(target())).isEqualTo(SOURCE);
        assertThat(world.out()).contains("MovCode0002 — Первый фильм").contains("ступень Easy · ведущая ось SEQ")
                .contains("hammerhall submit MovCode0002.java");
    }

    @Test
    @DisplayName("🔴 СР-5: набрали movcode0002 — файл называется как задача у платформы, а не как набрали")
    void canonicalName() {
        platform.on("GET", "/tasks/stream-forge/movcode0002", json(200, card("MovCode0002")));
        assertThat(world.run("pull", "movcode0002")).isZero();
        assertThat(target()).exists();
    }

    @Test
    @DisplayName("🔴 СР-4: флаг до номера — работает; файл есть — без --force отказ, решение не тронуто")
    void forceAnywhereAndRefusal() throws IOException {
        platform.on("GET", "/tasks/stream-forge/MovCode0002", json(200, card("MovCode0002")));
        Files.createDirectories(target().getParent());
        Files.writeString(target(), "моё решение за неделю");

        assertThat(world.run("pull", "MovCode0002")).isEqualTo(2);
        assertThat(world.err()).contains("Файл уже есть").contains("--force");
        assertThat(Files.readString(target())).isEqualTo("моё решение за неделю");

        assertThat(world.run("pull", "--force", "MovCode0002")).isZero();
        assertThat(Files.readString(target())).isEqualTo(SOURCE);
    }

    @Test
    @DisplayName("🔴 СР-6: --force откладывает прежний файл в .bak — и не затирает прежние .bak")
    void forceKeepsBackup() throws IOException {
        platform.on("GET", "/tasks/stream-forge/MovCode0002", json(200, card("MovCode0002")));
        Files.createDirectories(target().getParent());
        Files.writeString(target(), "версия 1");
        assertThat(world.run("pull", "MovCode0002", "--force")).isZero();
        Files.writeString(target(), "версия 2");
        assertThat(world.run("pull", "MovCode0002", "--force")).isZero();

        assertThat(Files.readString(target().resolveSibling("MovCode0002.java.bak"))).isEqualTo("версия 1");
        assertThat(Files.readString(target().resolveSibling("MovCode0002.java.bak.1"))).isEqualTo("версия 2");
        assertThat(world.out()).contains("Прежний файл отложен");
    }

    @Test
    @DisplayName("🔑 квота выдачи: 429 — текст платформы как есть, код 2")
    void quota() {
        platform.on("GET", "/tasks/stream-forge/MovCode0009", json(429,
                "{\"message\":\"За час можно взять 2 новые задачи — следующая через 37 мин.\",\"reason\":\"hour\"}",
                "Retry-After", "2220"));
        assertThat(world.run("pull", "MovCode0009")).isEqualTo(2);
        assertThat(world.err()).contains("следующая через 37 мин");
        assertThat(world.sleeps).isEmpty();
    }

    @Test
    @DisplayName("текста нет, пакет испорчен, номер негодный, вне кузницы — понятные отказы")
    void refusals() throws IOException {
        platform.on("GET", "/tasks/stream-forge/MovCode0003", json(200, "{\"id\":\"MovCode0003\"}"))
                .on("GET", "/tasks/stream-forge/MovCode0004",
                        json(200, Json.object("id", "MovCode0004", "source", "package io..evil;\nclass MovCode0004 {}")));
        assertThat(world.run("pull", "MovCode0003")).isEqualTo(2);
        assertThat(world.err()).contains("ещё не выложен");
        assertThat(world.run("pull", "MovCode0004")).isEqualTo(2);
        assertThat(world.err()).contains("Испорченный пакет");
        assertThat(world.run("pull", "../../etc/passwd")).isEqualTo(2);
        assertThat(world.err()).contains("не похоже на номер задачи");
        assertThat(world.run("pull")).isEqualTo(2);
        assertThat(world.err()).contains("hammerhall pull MovCode0002");

        world.workDir = Files.createDirectories(world.home.resolve("elsewhere"));
        assertThat(world.run("pull", "MovCode0002")).isEqualTo(2);
        assertThat(world.err()).contains("не похоже на кузницу");
    }

    @Test
    @DisplayName("без пакета — в корень исходников; карточка без названия — номер без висящего разделителя")
    void noPackageNoName() throws IOException {
        platform.on("GET", "/tasks/stream-forge/MovCode0005",
                json(200, Json.object("source", "void main() {\n}\n")));
        assertThat(world.run("pull", "MovCode0005")).isZero();
        assertThat(world.forge.resolve("src/main/java/MovCode0005.java")).exists();
        assertThat(world.out()).contains("MovCode0005").doesNotContain("—  ").doesNotContain("null");
    }

    @Test
    @DisplayName("разбор пакета: до первого объявления; package в комментарии ниже — не пакет")
    void packageParsing() {
        assertThat(PullCommand.packageOf("package a.b;\nclass X {}")).isEqualTo("a.b");
        assertThat(PullCommand.packageOf("import x.Y;\n// package fake;\n")).isNull();
        assertThat(PullCommand.packageOf("record R() {}\npackage late;")).isNull();
        assertThatThrownBy(() -> PullCommand.targetPath(world.forge, "package a/b;", "X"))
                .hasMessageContaining("Испорченный пакет");
    }
}
