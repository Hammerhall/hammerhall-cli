package io.hammerhall.cli.command;

import io.hammerhall.cli.ApiClient;
import io.hammerhall.cli.CliException;
import io.hammerhall.cli.Config;
import io.hammerhall.cli.Console;
import io.hammerhall.cli.ForgeRoot;
import io.hammerhall.cli.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Отправить решение на проверку.
 *
 * <h3>Что уезжает на сервер</h3>
 *
 * ТЕКСТ ОДНОГО ФАЙЛА — несколько килобайт. Не архив, не проект, не jar.
 * Датасеты (48 МБ) живут в образе песочницы и в клоне пользователя;
 * через платформу они не ходят ни разу.
 *
 * <h3>Файл ищется по имени</h3>
 *
 * Клиент не выходит за пределы кузницы, поэтому достаточно назвать файл:
 * {@code hammerhall submit MovCode0002.java}. Полный путь тоже примем, но
 * помнить его никто не обязан — задачи лежат глубоко в дереве пакетов.
 *
 * <p>{@code taskId} берётся из ИМЕНИ файла. Имя класса совпадает с id
 * задачи по стандарту кузницы, и это не совпадение, а правило: файл
 * {@code MovCode0002.java} — это задача {@code MovCode0002}.
 */
public class SubmitCommand {

    /** Тот же потолок, что на сервере: смысла отправлять больше нет. */
    private static final int MAX_CODE_BYTES = 256 * 1024;

    private final Console console;

    public SubmitCommand(Console console) {
        this.console = console;
    }

    public int run(String[] args) throws IOException {
        if (args.length == 0) {
            throw new CliException("""
                    Укажи файл:
                        hammerhall submit MovCode0002.java""");
        }

        Path root = ForgeRoot.find();
        Config config = Config.load(root);

        Path file = locate(root, args[0]);
        String taskId = taskIdOf(file);
        String code = read(file);
        String forgeId = forgeIdOf(root);

        console.dim("Отправляю " + taskId + "…");

        String response = new ApiClient(config.api(), config.token())
                .post("/submit", """
                        {"forgeId":"%s","taskId":"%s","code":"%s"}"""
                        .formatted(forgeId, taskId, Json.escape(code)));

        return report(response);
    }

    /** Печатает вердикт. Возвращает код выхода: 0 — прошло, 1 — нет. */
    private int report(String response) {
        String status = Json.string(response, "status");
        long duration = Json.number(response, "durationMs", 0);
        boolean fromHistory = Json.bool(response, "fromHistory", false);

        // tests — вложенный объект, поэтому вырезаем его и читаем отдельно.
        String tests = between(response, "\"tests\":{", "}");
        long total = Json.number("{" + tests + "}", "total", 0);
        long passed = Json.number("{" + tests + "}", "passed", 0);

        switch (status == null ? "" : status) {
            case "PASSED" -> console.success(
                    "PASSED  %d/%d тестов, %d мс".formatted(passed, total, duration));
            case "FAILED" -> {
                console.error("FAILED  %d/%d тестов".formatted(passed, total));
                printFailures(response);
            }
            case "STYLE_VIOLATION" -> {
                console.error("Решение не в стрим-стиле");
                printLog(response);
            }
            case "COMPILE_ERROR" -> {
                console.error("Не компилируется");
                printLog(response);
            }
            case "TIMEOUT" -> console.error("Слишком долго — проверка прервана");
            case "INVALID_INPUT" -> {
                console.error("Решение отклонено до запуска");
                printLog(response);
            }
            default -> {
                console.error("Вердикт: " + status);
                printLog(response);
            }
        }

        if (fromHistory) {
            // Иначе мгновенный ответ выглядит как сбой: человек ждал
            // секунду проверки, а получил результат сразу.
            console.dim("  (этот код уже проверялся — вердикт из истории)");
        }

        return "PASSED".equals(status) ? 0 : 1;
    }

    private void printFailures(String response) {
        List<String> failures = Json.strings(response, "failures");
        if (failures.isEmpty()) {
            return;
        }
        console.print("");
        console.print("Не прошли:");
        // Только ИМЕНА тестов — сервер и не присылает больше. Сообщения
        // ассертов содержали бы ожидаемые значения, а это подсказка к ответу.
        failures.forEach(name -> console.print("  · " + name));
    }

    private void printLog(String response) {
        String log = Json.string(response, "log");
        if (log != null && !log.isBlank()) {
            console.print("");
            log.lines().forEach(line -> console.print("  " + line));
        }
    }

    /**
     * Найти файл: по пути, если он существует, иначе поиском по имени
     * внутри {@code src/main/java}.
     */
    private Path locate(Path root, String argument) throws IOException {
        Path direct = Path.of(argument);
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        Path fromRoot = root.resolve(argument);
        if (Files.isRegularFile(fromRoot)) {
            return fromRoot;
        }

        String name = direct.getFileName().toString();
        if (!name.endsWith(".java")) {
            name = name + ".java";
        }

        Path sources = root.resolve("src/main/java");
        if (!Files.isDirectory(sources)) {
            throw new CliException("Нет каталога src/main/java — это точно кузница?");
        }

        List<Path> found;
        String target = name;
        try (Stream<Path> walk = Files.walk(sources)) {
            found = walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(target))
                    .toList();
        }

        if (found.isEmpty()) {
            throw new CliException("Файл не найден: " + name);
        }
        if (found.size() > 1) {
            // Не выбираем за человека: два файла с одним именем в разных
            // пакетах — это разные задачи, и угадывание отправило бы не ту.
            StringBuilder message = new StringBuilder("Файлов с таким именем несколько:\n");
            found.forEach(path -> message.append("    ").append(root.relativize(path)).append('\n'));
            message.append("Укажи путь целиком.");
            throw new CliException(message.toString());
        }
        return found.getFirst();
    }

    private String taskIdOf(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".java") ? name.substring(0, name.length() - ".java".length()) : name;
    }

    private String read(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length > MAX_CODE_BYTES) {
                throw new CliException("Файл больше " + (MAX_CODE_BYTES / 1024) + " КБ");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Какой кузнице принадлежит репозиторий.
     * <p>
     * Берём из {@code .hammerhall/forge}, а если файла нет — из имени
     * каталога. Имя каталога человек может переименовать при клонировании,
     * поэтому это запасной путь, а не основной.
     */
    private String forgeIdOf(Path root) {
        Path marker = root.resolve(".hammerhall/forge");
        if (Files.isRegularFile(marker)) {
            try {
                String value = Files.readString(marker, StandardCharsets.UTF_8).trim();
                if (!value.isBlank()) {
                    return value;
                }
            } catch (IOException ignored) {
                // читаем запасной путь ниже
            }
        }
        return root.getFileName().toString();
    }

    private static String between(String text, String from, String to) {
        int start = text.indexOf(from);
        if (start < 0) {
            return "";
        }
        start += from.length();
        int end = text.indexOf(to, start);
        return end < 0 ? "" : text.substring(start, end);
    }
}
