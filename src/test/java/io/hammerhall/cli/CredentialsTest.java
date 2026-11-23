package io.hammerhall.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ключи — в {@code ~/.hammerhall/credentials} (ревью CLI №1, АРХ-3,
 * решение владельца 23.11), с правами при создании (БЛОК-4) и переездом
 * ключа, оставшегося в кузнице от 0.1.x.
 */
@DisplayName("Credentials — ключи этой машины")
class CredentialsTest {

    @TempDir
    Path temp;

    private World world() throws IOException {
        return new World(temp);
    }

    @Test
    @DisplayName("сохранить, прочитать, удалить; порядок строк — как добавлялись")
    void roundTrip() throws IOException {
        World w = world();
        Credentials credentials = Credentials.load(w.home);
        Platform stand = new Platform(URI.create("http://localhost:8080"), true);
        assertThat(credentials.token(Platform.standard())).isNull();

        credentials.save(Platform.standard(), "boevoy-a1");
        credentials.save(stand, "stend-b2");
        Credentials again = Credentials.load(w.home);
        assertThat(again.token(Platform.standard())).isEqualTo("boevoy-a1");
        assertThat(again.require(stand)).isEqualTo("stend-b2");
        assertThat(Files.readString(w.credentials()))
                .contains("# Ключи доступа")
                .containsSubsequence("https://api.hammerhall.io=boevoy-a1", "http://localhost:8080=stend-b2");

        assertThat(again.remove(stand)).isTrue();
        assertThat(again.remove(stand)).isFalse();
        assertThatThrownBy(() -> Credentials.load(w.home).require(stand))
                .isInstanceOf(CliException.class).hasMessageContaining("localhost:8080");
        assertThatThrownBy(() -> Credentials.load(temp.resolve("пусто")).require(Platform.standard()))
                .isInstanceOf(CliException.class).hasMessageContaining("hammerhall login");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    @DisplayName("🔴 БЛОК-4: каталог 700 и файл 600 — с момента создания, и вторым проходом у старых")
    void ownerOnly() throws IOException {
        World w = world();
        Path dir = w.home.resolve(".hammerhall");
        Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")));
        Files.writeString(w.credentials(), "старое\n");
        Files.setPosixFilePermissions(w.credentials(), PosixFilePermissions.fromString("rw-r--r--"));

        Credentials.load(w.home).save(Platform.standard(), "k-1");

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir))).isEqualTo("rwx------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(w.credentials()))).isEqualTo("rw-------");
        try (var left = Files.list(dir)) {
            assertThat(left.map(p -> p.getFileName().toString())).containsExactly("credentials");   // временных нет
        }
    }

    @Test
    @DisplayName("переезд: ключ из кузницы — сюда, файл в кузнице — удалён")
    void migratesFromForge() throws IOException {
        World w = world();
        Path legacy = w.forge.resolve(".hammerhall/config");
        Files.writeString(legacy, "# старый конфиг\ntoken=old-key-1\n");

        Credentials credentials = Credentials.load(w.home);
        Console console = w.context().console();
        credentials.migrateFrom(w.forge, console);

        assertThat(credentials.token(Platform.standard())).isEqualTo("old-key-1");
        assertThat(legacy).doesNotExist();
        assertThat(w.forge.resolve(".hammerhall/forge")).exists();
        assertThat(w.out()).contains("перенесён");

        credentials.migrateFrom(w.forge, console);   // второй раз — нечего
        credentials.migrateFrom(null, console);
    }

    @Test
    @DisplayName("переезд: ключ стенда — под адрес стенда, не под боевой; лишние строки файла сохраняются")
    void migratesStandKeyToStand() throws IOException {
        World w = world();
        Path legacy = w.forge.resolve(".hammerhall/config");
        Files.writeString(legacy, "api=http://localhost:8080/\ntoken=stand-key\nnote=моё\n");

        Credentials credentials = Credentials.load(w.home);
        credentials.migrateFrom(w.forge, w.context().console());

        assertThat(credentials.token(Platform.standard())).isNull();
        assertThat(credentials.token(new Platform(URI.create("http://localhost:8080"), true))).isEqualTo("stand-key");
        assertThat(Files.readString(legacy)).isEqualTo("note=моё\n");
    }

    @Test
    @DisplayName("переезд: здесь ключ уже есть — старый из кузницы просто удаляется")
    void dropsDuplicate() throws IOException {
        World w = world();
        Credentials credentials = Credentials.load(w.home);
        credentials.save(Platform.standard(), "new-key");
        Path legacy = w.forge.resolve(".hammerhall/config");
        Files.writeString(legacy, "token=old-key\napi=https://api.hammerhall.io\n");

        credentials.migrateFrom(w.forge, w.context().console());

        assertThat(credentials.token(Platform.standard())).isEqualTo("new-key");
        assertThat(legacy).doesNotExist();
        assertThat(w.out()).contains("удалён");
    }

    @Test
    @DisplayName("переезд: непонятный адрес — не переносим и говорим, что делать")
    void badLegacyAddress() throws IOException {
        World w = world();
        Path legacy = w.forge.resolve(".hammerhall/config");
        Files.writeString(legacy, "api=http://evil.example\ntoken=x\n");
        Credentials.load(w.home).migrateFrom(w.forge, w.context().console());
        assertThat(legacy).exists();
        assertThat(w.out()).contains("не переносит");
        assertThat(Credentials.legacyApi(w.forge)).isEqualTo("http://evil.example");
        assertThat(Credentials.legacyApi(null)).isNull();
    }

    @Test
    @DisplayName("ключа нет, осталась только строка api= — убирается, чтобы не предупреждать вечно")
    void apiOnlyLegacy() throws IOException {
        World w = world();
        Path legacy = w.forge.resolve(".hammerhall/config");
        Files.writeString(legacy, "api=http://localhost:8080\n");
        Credentials.load(w.home).migrateFrom(w.forge, w.context().console());
        assertThat(legacy).doesNotExist();
    }

    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(OS.WINDOWS)
    @DisplayName("убрать ключ из кузницы не вышло — предупреждение, а не тишина")
    void leftover() throws IOException {
        World w = world();
        Path legacy = w.forge.resolve(".hammerhall/config");
        Files.writeString(legacy, "token=k\n");
        Path dir = legacy.getParent();
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            org.junit.jupiter.api.Assumptions.assumeFalse(Files.isWritable(dir), "под root права не мешают");
            Credentials.load(w.home).migrateFrom(w.forge, w.context().console());
            assertThat(w.out()).contains("Не удалось убрать ключ");
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    @DisplayName("испорченный файл ключей (каталог вместо файла) — понятные ошибки чтения и записи")
    void unreadable() throws IOException {
        World w = world();
        Files.createDirectories(w.credentials());   // каталог на месте файла
        Credentials credentials = Credentials.load(w.home);   // не файл — пусто
        assertThat(credentials.token(Platform.standard())).isNull();
        assertThatThrownBy(() -> credentials.save(Platform.standard(), "k"))
                .isInstanceOf(CliException.class).hasMessageContaining("Не удалось сохранить");
    }
}
