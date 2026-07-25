package io.hammerhall.cli.command;

import io.hammerhall.cli.ApiClient;
import io.hammerhall.cli.Config;
import io.hammerhall.cli.Console;
import io.hammerhall.cli.ForgeRoot;
import io.hammerhall.cli.Json;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Показать профиль по осям.
 *
 * <h3>Клиент ничего не считает</h3>
 *
 * Ни одной формулы здесь нет и быть не должно. Сервер присылает готовые
 * числа, клиент рисует полоски. Поменяются веса, порог фундамента или
 * способ свёртки — обновлять программу у всех не придётся.
 *
 * <p>Даже подпись «SEQ 3→4» после решения задачи формирует
 * {@code ProfileFormatter} на сервере.
 *
 * <h3>Профиль отдельный на каждую кузницу</h3>
 *
 * Оси Stream Forge (SET, PAIR, SEQ, CTX, JOIN, ALGO, SYS, PERF) не имеют
 * смысла в реактивной кузнице — там будут свои. Складывать их в один
 * вектор нельзя, поэтому кузница указывается явно.
 */
public class ProfileCommand {

    /** Ширина полоски. Восемь осей должны помещаться в узкий терминал. */
    private static final int BAR_WIDTH = 20;

    /** Потолок оси по стандарту кузницы — от него считается длина полоски. */
    private static final double MAX_SCORE = 12.5;

    private final Console console;

    public ProfileCommand(Console console) {
        this.console = console;
    }

    public int run(String[] args) {
        Path root = ForgeRoot.find();
        Config config = Config.load(root);
        ApiClient api = new ApiClient(config.api(), config.token());

        String forgeId = args.length > 0 ? args[0] : ForgeRoot.forgeId(root);
        String response = api.get("/profile/" + forgeId);

        print(forgeId, response);
        return 0;
    }

    /**
     * Отрисовка ответа платформы. Пакетная видимость — ради теста: здесь
     * живёт весь разбор JSON и всё форматирование, то есть ровно то, что
     * ломается молча при смене формы ответа.
     */
    void print(String forgeId, String response) {
        // ⚠️ Имена полей — КОНТРАКТ ПЛАТФОРМЫ, а не наши предпочтения:
        // ProfileController.ProfileResponse отдаёт solvedTasks и points.
        // Раньше здесь стояли tasksSolved и totalPoints — имена изнутри
        // клиента, — и Json.number молча возвращал значения по умолчанию.
        // Профиль показывал нули при трёх зачтённых задачах: ни ошибки,
        // ни предупреждения, просто ноль. Сверять с сервером, не выдумывать.
        long solved = Json.number(response, "solvedTasks", 0);
        long points = Json.number(response, "points", 0);

        console.print("");
        console.print("Кузница: " + forgeId);
        console.print("Решено задач: %d   Очков: %d".formatted(solved, points));
        console.print("");

        // Ответ платформы: {"axes":{"SEQ":{...},"JOIN":{...}}, ...}.
        // Имена осей — ключи объекта axes, отдельного списка нет.
        List<String> axes = axisNames(response);
        if (axes.isEmpty()) {
            // Пустой вектор — не ошибка, но причины у него две разные,
            // и путать их нельзя.
            if (solved > 0) {
                // Задачи засчитаны, а осей нет: у слоя code разметки осей
                // не бывает по устройству корпуса. Сказать «реши первую
                // задачу» человеку, решившему три, — значит соврать ему
                // в лицо и заставить искать несуществующую поломку.
                console.dim("Засчитанные задачи не несут осей — вектор пока пуст.");
            } else {
                console.dim("Профиль пуст — решите первую задачу.");
            }
            return;
        }

        String axesJson = objectOf(response, "axes");
        for (String axis : axes) {
            String axisJson = objectOf(axesJson, axis);
            double score = numberAsDouble(axisJson, "score");
            long ceiling = Json.number(axisJson, "ceiling", 0);
            double foundation = numberAsDouble(axisJson, "foundation");

            console.print("  %-5s %s  %4.1f   потолок %d, фундамент %.0f%%".formatted(
                    axis, bar(score), score, ceiling, foundation * 100));
        }
        console.print("");
    }

    /**
     * Имена осей — ключи объекта {@code axes}.
     * <p>
     * Порядок сохраняем тот, что прислал сервер: он идёт от сильных
     * к слабым (или как решит платформа), и пересортировывать на клиенте
     * значит спорить с тем, кто знает больше.
     */
    private List<String> axisNames(String response) {
        String axesJson = objectOf(response, "axes");
        List<String> names = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < axesJson.length(); i++) {
            char c = axesJson.charAt(i);
            if (inString) {
                if (c == '"') {
                    inString = false;
                    // Ключ верхнего уровня — тот, после которого идёт ':'
                    if (depth == 1 && next(axesJson, i) == ':') {
                        names.add(current.toString());
                    }
                    current.setLength(0);
                } else {
                    current.append(c);
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{' -> depth++;
                case '}' -> depth--;
                default -> {
                    // прочее не интересует
                }
            }
        }
        return names;
    }

    private char next(String text, int from) {
        for (int i = from + 1; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                return text.charAt(i);
            }
        }
        return '\0';
    }

    /**
     * Полоска. Не проценты и не звёздочки — глазом видно перекос профиля,
     * а он и есть главное, что профиль показывает.
     */
    private String bar(double score) {
        int filled = (int) Math.round(Math.min(score, MAX_SCORE) / MAX_SCORE * BAR_WIDTH);
        return "█".repeat(filled) + "·".repeat(BAR_WIDTH - filled);
    }

    /** Вырезает вложенный объект по имени поля. */
    private String objectOf(String json, String field) {
        String needle = '"' + field + "\":{";
        int start = json.indexOf(needle);
        if (start < 0) {
            return "{}";
        }
        start += needle.length() - 1;

        int depth = 0;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return json.substring(start, i + 1);
                }
            }
        }
        return "{}";
    }

    /**
     * Дробное число. {@link Json#number} отдаёт целое, а score и foundation
     * дробные — читаем вручную.
     */
    private double numberAsDouble(String json, String field) {
        String needle = '"' + field + "\":";
        int at = json.indexOf(needle);
        if (at < 0) {
            return 0;
        }
        int start = at + needle.length();
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        int end = start;
        while (end < json.length() && "-.0123456789eE".indexOf(json.charAt(end)) >= 0) {
            end++;
        }
        try {
            return Double.parseDouble(json.substring(start, end));
        } catch (RuntimeException e) {
            return 0;
        }
    }

}
