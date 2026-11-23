package io.hammerhall.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Мелкие части: разбор строки, идентификаторы, версия, {@code Retry-After}. */
@DisplayName("Мелкие части клиента")
class PartsTest {

    @Nested
    @DisplayName("Args — флаги в любом месте строки (СР-4)")
    class ArgsParsing {

        @Test
        @DisplayName("🔴 pull --force MovCode0002: флаг — флаг, номер — номер")
        void flagBeforePositional() {
            Args args = Args.parse(new String[]{"pull", "--force", "MovCode0002"});
            assertThat(args.positional()).containsExactly("pull", "MovCode0002");
            assertThat(args.rest()).containsExactly("MovCode0002");
            assertThat(args.has("--force")).isTrue();
        }

        @Test
        @DisplayName("--api со значением — через пробел и через «=»; «--» прекращает флаги")
        void valuedAndDoubleDash() {
            assertThat(Args.parse(new String[]{"--api", "http://x", "version"}).value("--api")).isEqualTo("http://x");
            assertThat(Args.parse(new String[]{"version", "--api=http://y"}).value("--api")).isEqualTo("http://y");
            Args args = Args.parse(new String[]{"submit", "--", "-странное.java"});
            assertThat(args.rest()).containsExactly("-странное.java");
            assertThat(Args.parse(new String[]{"submit", "-"}).rest()).containsExactly("-");
            assertThat(Args.parse(new String[0]).rest()).isEmpty();
        }

        @Test
        @DisplayName("--api без значения и значение у флага без значения — ошибка")
        void badValues() {
            assertThatThrownBy(() -> Args.parse(new String[]{"version", "--api"}))
                    .isInstanceOf(CliException.class).hasMessageContaining("--api");
            assertThatThrownBy(() -> Args.parse(new String[]{"pull", "--force=yes"}))
                    .isInstanceOf(CliException.class).hasMessageContaining("--force");
        }

        @Test
        @DisplayName("неизвестный команде флаг — ошибка с подсказкой, глобальные — понятны всем")
        void unknownFlag() {
            Args args = Args.parse(new String[]{"profile", "--wat", "--no-color"});
            assertThatThrownBy(() -> args.requireOnly("profile", Set.of()))
                    .isInstanceOf(CliException.class)
                    .hasMessageContaining("--wat").hasMessageContaining("profile");
            Args fine = Args.parse(new String[]{"pull", "--force", "--verbose", "--api", "http://x", "X"});
            fine.requireOnly("pull", Set.of("--force"));
        }

        @Test
        @DisplayName("лишнее и недостающее — с примером верного вызова")
        void counts() {
            Args extra = Args.parse(new String[]{"login", "a", "b"});
            assertThatThrownBy(() -> extra.requireCount(0, 1, "hammerhall login"))
                    .hasMessageContaining("b").hasMessageContaining("hammerhall login");
            Args none = Args.parse(new String[]{"pull"});
            assertThatThrownBy(() -> none.requireCount(1, 1, "hammerhall pull X"))
                    .hasMessageContaining("hammerhall pull X");
        }
    }

    @Nested
    @DisplayName("Ids — одно правило на все команды (ВЫС-9, СР-5)")
    class Identifiers {

        @Test
        @DisplayName("годные — проходят, сломанные — понятная ошибка")
        void validation() {
            assertThat(Ids.task("MovCode0002")).isEqualTo("MovCode0002");
            assertThat(Ids.forge("stream-forge")).isEqualTo("stream-forge");
            for (String bad : List.of("MovCode0002 (копия)", "../x", "", "1abc", "Mov-Code")) {
                assertThatThrownBy(() -> Ids.task(bad)).isInstanceOf(CliException.class);
            }
            for (String bad : List.of("hammerhall \"рабочий\"", "a/b", "-x", "")) {
                assertThatThrownBy(() -> Ids.forge(bad)).isInstanceOf(CliException.class);
            }
            assertThatThrownBy(() -> Ids.task(null)).isInstanceOf(CliException.class);
            assertThatThrownBy(() -> Ids.forge(null)).isInstanceOf(CliException.class);
        }

        @Test
        @DisplayName("сегмент пути кодируется: пробел — %20, слэш — %2F")
        void segments() {
            assertThat(Ids.segment("a b/c")).isEqualTo("a%20b%2Fc");
            assertThat(Ids.segment("stream-forge")).isEqualTo("stream-forge");
        }
    }

    @Nested
    @DisplayName("Version — клиент знает свою версию (БЛОК-2)")
    class Versions {

        @Test
        @DisplayName("сравнение по числам: 0.10.0 новее 0.9.9; хвост -SNAPSHOT не мешает; dev — всегда свежая")
        void compare() {
            assertThat(Version.isOlder("0.9.9", "0.10.0")).isTrue();
            assertThat(Version.isOlder("0.10.0", "0.9.9")).isFalse();
            assertThat(Version.isOlder("0.2.0", "0.2.0")).isFalse();
            assertThat(Version.isOlder("0.2", "0.2.1")).isTrue();
            assertThat(Version.isOlder("0.2.0-SNAPSHOT", "0.2.0")).isFalse();
            assertThat(Version.isOlder(Version.DEV, "9.9.9")).isFalse();
            assertThat(Version.isOlder("мусор", "0.2.0")).isFalse();
            assertThat(Version.isOlder("0.2.0", "мусор")).isFalse();
            assertThat(Version.isOlder(null, "0.2.0")).isFalse();
        }

        @Test
        @DisplayName("User-Agent: имя, версия, Java и система")
        void userAgent() {
            assertThat(Version.userAgent("0.2.0"))
                    .startsWith("hammerhall-cli/0.2.0 (java ")
                    .contains(System.getProperty("os.name"));
        }

        @Test
        @DisplayName("из тестов манифеста нет — версия dev")
        void devWithoutManifest() {
            assertThat(Version.current()).isEqualTo(Version.DEV);
        }
    }

    @Nested
    @DisplayName("RetryAfter — секунды и HTTP-дата (ВЫС-11)")
    class Retry {

        private final Clock clock = Clock.fixed(Instant.parse("2026-08-05T14:32:00Z"), ZoneOffset.UTC);

        @Test
        @DisplayName("🔴 дата не печатается как есть — считается разница с «сейчас»")
        void date() {
            assertThat(RetryAfter.parse("Wed, 05 Aug 2026 14:32:11 GMT", clock)).isEqualTo(Duration.ofSeconds(11));
            assertThat(RetryAfter.parse("Wed, 05 Aug 2026 14:00:00 GMT", clock)).isEqualTo(Duration.ZERO);
        }

        @Test
        @DisplayName("секунды, пусто, мусор")
        void seconds() {
            assertThat(RetryAfter.parse("30", clock)).isEqualTo(Duration.ofSeconds(30));
            assertThat(RetryAfter.parse(" ", clock)).isNull();
            assertThat(RetryAfter.parse(null, clock)).isNull();
            assertThat(RetryAfter.parse("скоро", clock)).isNull();
            assertThat(RetryAfter.parse("99999999999999999999999", clock)).isNull();
        }

        @Test
        @DisplayName("по-человечески: 43 200 с — это «12 ч»")
        void human() {
            assertThat(RetryAfter.human(Duration.ofSeconds(5))).isEqualTo("5 с");
            assertThat(RetryAfter.human(Duration.ofSeconds(61))).isEqualTo("2 мин");
            assertThat(RetryAfter.human(Duration.ofSeconds(43_200))).isEqualTo("12 ч");
            assertThat(RetryAfter.human(Duration.ofMinutes(130))).isEqualTo("2 ч 10 мин");
            assertThat(RetryAfter.human(Duration.ofHours(24))).isEqualTo("1 сут");
            assertThat(RetryAfter.human(Duration.ofHours(27))).isEqualTo("1 сут 3 ч");
            assertThat(RetryAfter.human(Duration.ofSeconds(-3))).isEqualTo("0 с");
        }
    }

    @Test
    @DisplayName("ВЫС-7: ввод не из терминала — секрет читается строкой, приглашение видно, и hidden() честно false")
    void systemInput() {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        Console console = new Console(new java.io.PrintStream(out, true, java.nio.charset.StandardCharsets.UTF_8),
                new java.io.PrintStream(new java.io.ByteArrayOutputStream()));
        Input input = Input.system(console, new java.io.ByteArrayInputStream(
                "почта@x\nпароль\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(input.hidden()).isFalse();
        assertThat(input.line()).isEqualTo("почта@x");
        assertThat(new String(input.secret("Пароль: "))).isEqualTo("пароль");
        assertThat(input.secret("Ещё: ")).isEmpty();
        assertThat(input.line()).isNull();
        assertThat(out.toString(java.nio.charset.StandardCharsets.UTF_8)).contains("Пароль: ");
    }

    @Test
    @DisplayName("CliException: вид ошибки задаёт код возврата")
    void exitCodes() {
        assertThat(new CliException("x").kind().exitCode()).isEqualTo(2);
        assertThat(new CliException("x", 503, CliException.Kind.UNAVAILABLE).kind().exitCode()).isEqualTo(3);
        assertThat(CliException.Kind.TIMEOUT.exitCode()).isEqualTo(3);
        assertThat(new CliException("x", new RuntimeException()).status()).isZero();
    }
}
