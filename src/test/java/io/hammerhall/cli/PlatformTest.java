package io.hammerhall.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Адрес платформы (ревью CLI №1, БЛОК-3): прибит по умолчанию,
 * переопределяется только снаружи репозитория и громко, а строка
 * {@code api=} в файле кузницы — путь утечки пароля — больше не действует.
 */
@DisplayName("Platform — куда уходят ключ и пароль")
class PlatformTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final Console console = new Console(new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));

    private String printed() {
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("по умолчанию — боевой адрес, молча")
    void standard() {
        Platform platform = Platform.resolve(null, null, null, console);
        assertThat(platform.toString()).isEqualTo(Platform.DEFAULT);
        assertThat(platform.overridden()).isFalse();
        assertThat(platform.key()).isEqualTo(Platform.DEFAULT);
        assertThat(printed()).isEmpty();
    }

    @Test
    @DisplayName("🔴 api= из файла кузницы — НЕ действует, и клиент об этом говорит")
    void legacyFileIgnored() {
        Platform platform = Platform.resolve(null, null, "http://evil.example", console);
        assertThat(platform.toString()).isEqualTo(Platform.DEFAULT);
        assertThat(printed()).contains("api=http://evil.example").contains("больше не действует");
    }

    @Test
    @DisplayName("api= в файле, совпадающий с боевым, — молчим")
    void legacyDefaultSilent() {
        Platform.resolve(null, null, "https://api.hammerhall.io/", console);
        assertThat(printed()).isEmpty();
    }

    @Test
    @DisplayName("переменная окружения и флаг — громкая строка; флаг сильнее переменной")
    void overrides() {
        Platform fromEnv = Platform.resolve(null, "http://localhost:8080/", null, console);
        assertThat(fromEnv.overridden()).isTrue();
        assertThat(fromEnv.toString()).isEqualTo("http://localhost:8080");
        assertThat(fromEnv.key()).isEqualTo("http://localhost:8080");
        assertThat(printed()).contains("переопределён").contains("http://localhost:8080");

        Platform fromFlag = Platform.resolve("https://stand.example", "http://localhost:8080", null, console);
        assertThat(fromFlag.toString()).isEqualTo("https://stand.example");
        assertThat(Platform.resolve(null, "https://api.hammerhall.io", null, console).overridden()).isFalse();
    }

    @Test
    @DisplayName("🔴 http:// — только для своей машины: по открытой сети ключ уехал бы открытым текстом")
    void plainHttpOnlyLoopback() {
        assertThatThrownBy(() -> Platform.resolve("http://api.example.com", null, null, console))
                .isInstanceOf(CliException.class).hasMessageContaining("без шифрования");
        assertThat(Platform.resolve("http://127.0.0.1:9", null, null, console).overridden()).isTrue();
        assertThat(Platform.resolve("http://[::1]:9", null, null, console).overridden()).isTrue();
        assertThat(Platform.isLoopback("localhost")).isTrue();
        assertThat(Platform.isLoopback("10.0.0.1")).isFalse();
        assertThat(Platform.isLoopback("localhost.evil.example")).isFalse();
    }

    @Test
    @DisplayName("мусор вместо адреса — понятная ошибка")
    void garbage() {
        for (String bad : new String[]{"ftp://x", "api.hammerhall.io", "https://x/?q=1", "https://u:p@x", "https://x/#f",
                "http://[::zz", "https://"}) {
            assertThatThrownBy(() -> Platform.resolve(bad, null, null, console))
                    .as(bad).isInstanceOf(CliException.class).hasMessageContaining("адрес");
        }
    }
}
