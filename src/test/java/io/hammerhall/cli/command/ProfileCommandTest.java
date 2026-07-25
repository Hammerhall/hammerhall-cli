package io.hammerhall.cli.command;

import io.hammerhall.cli.Console;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Профиль: разбор ответа платформы и отрисовка.
 *
 * <h3>Что охраняется</h3>
 *
 * Клиент здесь ничего не считает — все числа приходят с сервера, — но
 * разбирает JSON вручную, включая вырезание вложенных объектов и чтение
 * дробных чисел. Это тот код, который при смене формы ответа не падает,
 * а МОЛЧА рисует нули: человек решит, что потерял прогресс.
 *
 * <p>Отдельно охраняется порядок осей. Сервер присылает их от сильных к
 * слабым, и пересортировка на клиенте была бы спором с тем, кто знает больше.
 */
@DisplayName("ProfileCommand — отрисовка профиля")
class ProfileCommandTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private final ProfileCommand command = new ProfileCommand(new Console(
            new PrintStream(out, true, UTF_8), new PrintStream(err, true, UTF_8)));

    /** Вывод без ANSI-раскраски — цвет зависит от наличия консоли, текст нет. */
    private String printed() {
        return (out.toString(UTF_8) + err.toString(UTF_8))
                .replaceAll("\u001B\\[[0-9;]*m", "");
    }

    /**
     * ⚠️ Фикстура списана с РЕАЛЬНОГО ответа платформы
     * ({@code ProfileController.ProfileResponse}), а не придумана из кода
     * клиента.
     * <p>
     * В первой версии этого теста поля назывались {@code tasksSolved} и
     * {@code totalPoints} — так их читал клиент. Тест был зелёный, а платформа
     * всё это время отдавала {@code solvedTasks} и {@code points}. Профиль
     * показывал нули при трёх зачтённых задачах, и ни одна проверка этого не
     * поймала: тест сверял клиент сам с собой.
     * <p>
     * Правило отсюда: фикстуру для чужого контракта берут у владельца
     * контракта. Придуманная фикстура проверяет фантазию автора.
     */
    private static final String PROFILE = """
            {"forgeId":"stream-forge","forgeName":"Stream Forge",
             "solvedTasks":12,"points":47,
             "axes":{
               "SEQ":{"score":4.2,"ceiling":5,"creditedCeiling":4.6,"workload":3.1,"foundation":0.6},
               "JOIN":{"score":1.5,"ceiling":2,"creditedCeiling":1.8,"workload":1.2,"foundation":0.25}
             }}""";

    @Test
    @DisplayName("шапка: кузница, решено задач, очки")
    void showsHeader() {
        command.print("stream-forge", PROFILE);

        assertThat(printed())
                .contains("stream-forge")
                .contains("Решено задач: 12")
                .contains("47");
    }

    @Test
    @DisplayName("оси показаны с потолком и фундаментом")
    void showsAxes() {
        command.print("stream-forge", PROFILE);

        // Числа не сверяем дословно: формат дробных зависит от локали
        // (в русской «4,2», в английской «4.2»). Сверяем то, что от локали
        // не зависит — что ось и её потолок вообще доехали до человека.
        assertThat(printed())
                .contains("SEQ")
                .contains("потолок 5")
                .contains("JOIN")
                .contains("потолок 2");
    }

    @Test
    @DisplayName("🔴 порядок осей — как прислал сервер, без пересортировки")
    void keepsServerOrder() {
        command.print("stream-forge", PROFILE);
        String text = printed();

        assertThat(text.indexOf("SEQ"))
                .as("SEQ пришла первой и должна остаться первой")
                .isLessThan(text.indexOf("JOIN"));
    }

    @Test
    @DisplayName("пустой профиль — приглашение, а не нули по осям")
    void emptyProfileIsInvitation() {
        // Человек только зарегистрировался. Нули по восьми осям выглядели бы
        // как поражение на старте.
        command.print("stream-forge", """
                {"forgeId":"stream-forge","solvedTasks":0,"points":0,"axes":{}}""");

        assertThat(printed()).contains("Профиль пуст");
    }

    @Test
    @DisplayName("🔴 задачи засчитаны, но осей нет — НЕ «решите первую задачу»")
    void solvedWithoutAxesIsExplained() {
        // Слой code не несёт разметки осей по устройству корпуса. Сказать
        // «решите первую задачу» человеку, решившему три, значит соврать
        // и отправить его искать несуществующую поломку.
        command.print("stream-forge", """
                {"forgeId":"stream-forge","solvedTasks":3,"points":0,"axes":{}}""");

        assertThat(printed())
                .contains("Решено задач: 3")
                .contains("не несут осей");
    }

    @Test
    @DisplayName("🔴 ответ без осей вообще — не падаем")
    void survivesMissingAxes() {
        command.print("stream-forge", """
                {"forgeId":"stream-forge","solvedTasks":0,"points":0}""");

        assertThat(printed()).contains("Профиль пуст");
    }
}
