package io.hammerhall.cli.command;

import io.hammerhall.cli.CliException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Что принимаем за токен, а что отбиваем сразу.
 *
 * <h3>Почему это важнее, чем кажется</h3>
 *
 * Токен уезжает в заголовок {@code Authorization}, а значения HTTP-заголовков
 * обязаны быть ASCII. На кириллице {@code HttpClient} из JDK бросает
 * {@code IllegalArgumentException} — и человек, вставивший не то, видит
 * стектрейс, как при поломке платформы, вместо «токен выглядит неправильно».
 *
 * <p>Нашлось это случайно: в тесте {@code ApiClientTest} токен сначала был
 * написан по-русски, и восемь проверок легли разом. Ошибка теста оказалась
 * дырой в продукте.
 */
@DisplayName("LoginCommand — проверка токена до сохранения")
class LoginCommandTest {

    @Test
    @DisplayName("пробелы по краям обрезаются молча")
    void trimsSurroundingSpaces() {
        // Частый результат копирования из браузера: выделилось с запасом.
        // Человек в этом не виноват, ругаться незачем.
        assertThat(LoginCommand.normalize("  a1b2c3d4  ")).isEqualTo("a1b2c3d4");
        assertThat(LoginCommand.normalize("\ta1b2c3d4\n")).isEqualTo("a1b2c3d4");
    }

    @Test
    @DisplayName("обычный токен проходит как есть")
    void acceptsHexToken() {
        String token = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

        assertThat(LoginCommand.normalize(token)).isEqualTo(token);
    }

    @Test
    @DisplayName("пусто — внятный отказ, а не попытка сохранить пустоту")
    void rejectsBlank() {
        assertThatThrownBy(() -> LoginCommand.normalize(""))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("Пустой токен");
        assertThatThrownBy(() -> LoginCommand.normalize("   "))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("Пустой токен");
        assertThatThrownBy(() -> LoginCommand.normalize(null))
                .isInstanceOf(CliException.class);
    }

    @Test
    @DisplayName("🔴 кириллица отбивается СЛОВАМИ, а не стектрейсом из HttpClient")
    void rejectsNonAscii() {
        assertThatThrownBy(() -> LoginCommand.normalize("мой-секретный-токен"))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("выглядит неправильно");
    }

    @Test
    @DisplayName("🔴 токен с пробелом внутри — тоже отказ: заголовок такого не переживёт")
    void rejectsInnerWhitespace() {
        // Скопировали две строки разом или подхватили перенос — заголовок
        // с пробелом внутри платформа не примет, а сломается это далеко
        // от места ошибки.
        assertThatThrownBy(() -> LoginCommand.normalize("a1b2 c3d4"))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("выглядит неправильно");
    }
}
