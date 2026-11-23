package io.hammerhall.cli.command;

import io.hammerhall.cli.FakePlatform;
import io.hammerhall.cli.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static io.hammerhall.cli.FakePlatform.json;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("profile — профиль по осям и список кузниц")
class ProfileCommandTest {

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

    @Test
    @DisplayName("🔴 ВЫС-1: оси в ответе с пробелами — рисуются, а не «профиль пуст»; порядок — как прислал сервер")
    void prettyAxes() {
        platform.on("GET", "/profile/stream-forge", json(200, """
                {
                  "forgeName" : "Stream Forge",
                  "solvedTasks" : 3,
                  "points" : 40,
                  "axes" : {
                    "SEQ" : { "score" : 6.25, "ceiling" : 3, "foundation" : 0.7 },
                    "JOIN" : { "score" : 0, "ceiling" : 0, "foundation" : 0.004 }
                  }
                }"""));
        assertThat(world.run("profile")).isZero();
        assertThat(world.out())
                .contains("Stream Forge (stream-forge)")
                .contains("Решено задач: 3   Очков: 40")
                .containsSubsequence("SEQ", "JOIN")
                .contains("██████████··········   6.3   потолок 3, фундамент 70%")
                .contains("фундамент <1%")
                .doesNotContain("Профиль пуст");
    }

    @Test
    @DisplayName("🔴 СР-1: потолок полоски — с сервера (fill, maxScore, axisMax); 12.5 — только запасное")
    void ceilingFromServer() {
        platform.on("GET", "/profile/spring-context", json(200, """
                {"axisMax":5,"axes":{
                   "A":{"score":2.5},
                   "B":{"score":2.5,"maxScore":10},
                   "C":{"score":1,"fill":1.0}}}"""));
        assertThat(world.run("profile", "spring-context")).isZero();
        String out = world.out();
        assertThat(out).contains("A     " + "█".repeat(10) + "·".repeat(10));
        assertThat(out).contains("B     " + "█".repeat(5) + "·".repeat(15));
        assertThat(out).contains("C     " + "█".repeat(20));
        assertThat(ProfileCommand.bar(-1)).isEqualTo("·".repeat(20));
        assertThat(ProfileCommand.bar(7)).isEqualTo("█".repeat(20));
    }

    @Test
    @DisplayName("🔴 СР-3: русская локаль машины — всё равно «4.5», а не «4,5»")
    void rootLocale() {
        Locale before = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("ru-RU"));
        try {
            platform.on("GET", "/profile/stream-forge",
                    json(200, "{\"axes\":{\"SEQ\":{\"score\":4.5,\"ceiling\":3,\"foundation\":0.5}}}"));
            assertThat(world.run("profile")).isZero();
            assertThat(world.out()).contains(" 4.5 ").doesNotContain("4,5");
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    @DisplayName("пустой вектор: решено ноль — «решите первую»; решено, но осей нет — не враньё")
    void emptyVectors() {
        platform.on("GET", "/profile/stream-forge",
                json(200, "{\"solvedTasks\":0,\"axes\":{}}"),
                json(200, "{\"solvedTasks\":3,\"axes\":{}}"));
        world.run("profile");
        assertThat(world.out()).contains("решите первую задачу");
        world.clear();
        world.run("profile");
        assertThat(world.out()).contains("не несут осей").doesNotContain("решите первую");
    }

    @Test
    @DisplayName("🔴 ВЫС-2: вне кузницы — список кузниц (ответ-массив); профиль --help — справка, а не GET /profile/--help")
    void forgesOutside() throws IOException {
        platform.on("GET", "/profile", json(200, """
                [{"id":"stream-forge","displayName":"Stream Forge","solvedTasks":3,"submissions":9},
                 {"id":"spring-context","displayName":null,"solvedTasks":0,"submissions":0}]"""));
        world.workDir = Files.createDirectories(world.home.resolve("elsewhere"));
        assertThat(world.run("profile")).isZero();
        assertThat(world.out()).contains("stream-forge").contains("Stream Forge").contains("решено   3, отправок 9")
                .contains("spring-context").contains("hammerhall profile <кузница>");

        assertThat(world.run("profile", "--help")).isZero();
        assertThat(platform.seen()).noneMatch(s -> s.path().contains("--help"));

        platform.on("GET", "/profile", json(200, "[]"));
        world.clear();
        world.run("profile");
        assertThat(world.out()).contains("Кузниц пока нет");
    }

    @Test
    @DisplayName("ВЫС-9: негодный идентификатор кузницы — отказ до запроса")
    void badForgeArgument() {
        assertThat(world.run("profile", "a/b")).isEqualTo(2);
        assertThat(world.err()).contains("не похоже на идентификатор кузницы");
        assertThat(world.run("profile", "a", "b")).isEqualTo(2);
    }
}
