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
 * Поиск кузницы (ревью CLI №1, СР-10): по маркеру, с остановкой на границе
 * репозитория и на домашнем каталоге — а не до корня диска по паре
 * «pom.xml рядом с src/main/java».
 */
@DisplayName("ForgeRoot — где кузница и какая")
class ForgeRootTest {

    @TempDir
    Path temp;

    @Test
    @DisplayName("из глубины пакетов — находим корень по маркеру")
    void fromDeepInside() throws IOException {
        World w = new World(temp);
        Path deep = Files.createDirectories(w.forge.resolve("src/main/java/io/hammerhall/forge/stream"));
        ForgeRoot.Found found = ForgeRoot.find(deep, w.home);
        assertThat(found.root()).isEqualTo(w.forge);
        assertThat(found.id()).isEqualTo("stream-forge");
        assertThat(found.guessed()).isFalse();
    }

    @Test
    @DisplayName("🔴 модуль многомодульного проекта — не кузница: подъём идёт до границы репозитория")
    void moduleIsNotRoot() throws IOException {
        World w = new World(temp);
        Path module = Files.createDirectories(w.forge.resolve("module-a/src/main/java"));
        Files.writeString(w.forge.resolve("module-a/pom.xml"), "<project/>");
        assertThat(ForgeRoot.find(module, w.home).root()).isEqualTo(w.forge);
    }

    @Test
    @DisplayName("🔴 чужой Maven-проект выше по дереву — не кузница: стоп на домашнем каталоге и на .git")
    void stopsAtBoundaries() throws IOException {
        Path home = Files.createDirectories(temp.resolve("h"));
        // Maven-проект ВЫШЕ дома — раньше клиент принял бы его за кузницу.
        Files.writeString(temp.resolve("pom.xml"), "<project/>");
        Files.createDirectories(temp.resolve("src/main/java"));
        Path somewhere = Files.createDirectories(home.resolve("docs/notes"));
        assertThat(ForgeRoot.search(somewhere, home)).isNull();
        assertThatThrownBy(() -> ForgeRoot.find(somewhere, home))
                .isInstanceOf(CliException.class).hasMessageContaining("не похоже на кузницу");

        // Репозиторий без признаков кузницы — граница, выше не идём.
        Path repo = Files.createDirectories(home.resolve("other"));
        Files.createDirectories(repo.resolve(".git"));
        assertThat(ForgeRoot.search(repo, home)).isNull();
        assertThat(ForgeRoot.search(temp.resolve("x-нет"), null)).isNull();
    }

    @Test
    @DisplayName("кузница старого образца без маркера — имя каталога, и вслух")
    void guessedFromDirectory() throws IOException {
        World w = new World(temp);
        Files.delete(w.forge.resolve(".hammerhall/forge"));
        ForgeRoot.Found found = ForgeRoot.find(w.forge, w.home);
        assertThat(found.id()).isEqualTo("stream-forge");
        assertThat(found.guessed()).isTrue();

        w.context().forgeOrNull();
        assertThat(w.out()).contains("по имени каталога");
    }

    @Test
    @DisplayName("каталог с негодным именем и пустой маркер — понятные ошибки")
    void badNames() throws IOException {
        Path home = Files.createDirectories(temp.resolve("h"));
        Path odd = Files.createDirectories(home.resolve("моя кузница"));
        Files.createDirectories(odd.resolve(".git"));
        Files.createDirectories(odd.resolve("src/main/java"));
        Files.writeString(odd.resolve("pom.xml"), "<project/>");
        assertThatThrownBy(() -> ForgeRoot.find(odd, home)).hasMessageContaining("моя кузница");

        World w = new World(temp.resolve("w"));
        Files.writeString(w.forge.resolve(".hammerhall/forge"), "  \n");
        assertThatThrownBy(() -> ForgeRoot.find(w.forge, w.home)).hasMessageContaining("Пустой");

        Files.delete(w.forge.resolve(".hammerhall/forge"));
        Files.createDirectories(w.forge.resolve(".hammerhall/forge"));   // каталог вместо файла
        assertThat(ForgeRoot.find(w.forge, w.home).guessed()).isTrue();
    }

    @Test
    @DisplayName("путь для человека — от корня кузницы и через «/» на любой системе")
    void shownPath() {
        Path root = temp.resolve("forge");
        Path file = root.resolve("src").resolve("main").resolve("java").resolve("io").resolve("MovCode0002.java");
        assertThat(ForgeRoot.shown(root, file)).isEqualTo("src/main/java/io/MovCode0002.java");
    }
}
