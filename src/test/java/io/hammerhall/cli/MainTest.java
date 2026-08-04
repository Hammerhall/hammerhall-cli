package io.hammerhall.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Справка — единственный текст, который человек читает ДО первой команды.
 *
 * <p>🔴 Она дважды за два дня откатывалась к редакции 0.1.1 и дважды уезжала
 * в релиз: файл приходил собранным из устаревшего снимка дерева, а Main
 * покрыт на ноль — по решению, записанному в COVERAGE как намеренное.
 * Решение верно для run(), где настоящая файловая система и сеть, и неверно
 * для константы, которую можно прочитать строкой.
 */
@DisplayName("Main — справка обещает то, что клиент умеет")
class MainTest {

    @Test
    @DisplayName("перечислены ВСЕ команды, включая login --token")
    void listsEveryCommand() {
        assertThat(Main.USAGE)
                .contains("hammerhall login")
                .contains("hammerhall login --token")
                .contains("hammerhall pull")
                .contains("hammerhall submit")
                .contains("hammerhall profile");
    }

    @Test
    @DisplayName("🔴 нет обещаний, переставших быть правдой в 0.1.2")
    void makesNoObsoletePromises() {
        // Регистрация ключей не выпускает с V7, кнопки в кабинете нет.
        // Ровно этот текст возвращался дважды — положительный контроль
        // к предыдущему тесту: тот проходит и на старой справке, если
        // к ней просто дописать строку.
        assertThat(Main.USAGE)
                .doesNotContain("сохранить токен доступа")
                .doesNotContain("выдаётся при регистрации")
                .doesNotContain("показывается ОДИН раз");
    }

    @Test
    @DisplayName("почта и пароль названы — это и есть новый способ входа")
    void explainsHowToSignIn() {
        assertThat(Main.USAGE).contains("Почта и пароль");
    }
}