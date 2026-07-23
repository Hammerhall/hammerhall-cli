package io.hammerhall.cli.command;

import io.hammerhall.cli.ApiClient;
import io.hammerhall.cli.CliException;
import io.hammerhall.cli.Config;
import io.hammerhall.cli.Console;
import io.hammerhall.cli.ForgeRoot;
import io.hammerhall.cli.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * Забрать задачу из кузницы и положить файл в свой проект.
 *
 * <h3>Зачем команда существует</h3>
 *
 * Закрывает сценарий «бесплатные кончились, оплатил, хочу ещё». Без неё
 * человек может только решать то, что уже лежит в его клоне: новые задачи
 * взять неоткуда, а раздавать их форком репозитория — значит раздавать
 * весь корпус сразу, включая платный.
 *
 * <p>Платформа проверяет уровень доступа сама и на закрытую задачу отвечает
 * 404, а не 403: подтверждать существование недоступной задачи не нужно.
 * Клиент этой логики не знает и знать не должен.
 *
 * <h3>Куда кладётся файл</h3>
 *
 * В пакет, объявленный в самом тексте задачи. Пакет не угадывается и не
 * настраивается: стандарт кузницы требует, чтобы пакет задачи отражал её
 * место в лестнице, и единственный правдивый источник — присланный текст.
 *
 * <h3>Чужую работу не затираем</h3>
 *
 * Если файл уже есть, команда отказывается работать. Человек мог решать эту
 * задачу неделю, и молчаливая перезапись стёрла бы решение без возможности
 * восстановить. Перезапись — только явным {@code --force}.
 */
public class PullCommand {

    /**
     * Идентификатор задачи попадает и в URL, и в имя файла, поэтому
     * проверяется до всякого использования: {@code ../../etc/passwd} не
     * должен доехать ни до сервера, ни до файловой системы.
     */
    private static final Pattern TASK_ID = Pattern.compile("[A-Za-z][A-Za-z0-9]{0,31}");

    /** Часть пакета — обычный Java-идентификатор, и ничего больше. */
    private static final Pattern PACKAGE_PART = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final Console console;

    public PullCommand(Console console) {
        this.console = console;
    }

    public int run(String[] args) throws IOException {
        if (args.length == 0) {
            throw new CliException("""
                    Укажите задачу:
                        hammerhall pull MovCode0002""");
        }

        boolean force = Arrays.asList(args).contains("--force");
        String taskId = taskIdOf(args[0]);

        Path root = ForgeRoot.find();
        Config config = Config.load(root);
        String forgeId = ForgeRoot.forgeId(root);

        console.dim("Забираю " + taskId + "…");

        String response = new ApiClient(config.api(), config.token())
                .get("/tasks/" + forgeId + "/" + taskId);

        String source = Json.string(response, "source");
        if (source == null || source.isBlank()) {
            // Задача в каталоге есть, а текста нет: артефакт выдачи ещё не
            // залит на платформу. Это не ошибка человека, и говорить надо так.
            throw new CliException(
                    "Текст задачи " + taskId + " ещё не выложен на платформу. "
                            + "Это не ваша ошибка — попробуйте позже.");
        }

        Path target = targetPath(root, source, taskId);
        write(target, source, force);

        report(response, taskId, root.relativize(target));
        return 0;
    }

    // ---------- разбор аргумента ----------

    /** Принимаем и {@code MovCode0002}, и {@code MovCode0002.java}. */
    static String taskIdOf(String argument) {
        String id = argument.endsWith(".java")
                ? argument.substring(0, argument.length() - ".java".length())
                : argument;

        if (!TASK_ID.matcher(id).matches()) {
            throw new CliException("Это не похоже на идентификатор задачи: " + argument);
        }
        return id;
    }

    // ---------- куда класть ----------

    /**
     * Путь файла: {@code src/main/java} + пакет из текста задачи + имя класса.
     * <p>
     * Пакета в тексте нет — кладём в корень исходников. Такого по стандарту
     * кузницы быть не должно, но падать из-за этого незачем: файл человек
     * получит, а поправить место сможет сам.
     */
    static Path targetPath(Path root, String source, String taskId) {
        Path dir = root.resolve("src/main/java");
        String declared = packageOf(source);

        if (declared != null) {
            // split с -1: без него Java МОЛЧА отбрасывает пустые хвосты, и
            // испорченный «io...» превращался бы в невинный «io» — файл уехал
            // бы не туда, а человек не узнал бы почему. Пустая часть здесь
            // такой же признак поломки, как слэш.
            for (String part : declared.split("\\.", -1)) {
                if (!PACKAGE_PART.matcher(part).matches()) {
                    // Пакет пришёл с сервера, и доверять ему на слово нельзя:
                    // «..» или слэш увели бы запись за пределы кузницы.
                    throw new CliException("Испорченный пакет в тексте задачи: " + declared);
                }
                dir = dir.resolve(part);
            }
        }
        return dir.resolve(taskId + ".java");
    }

    /**
     * Объявление пакета из исходника.
     * <p>
     * Ищем до первого {@code import} или объявления типа: дальше объявления
     * пакета быть не может, а слово «package» в комментарии ниже — запросто.
     */
    static String packageOf(String source) {
        for (String raw : source.lines().toList()) {
            String line = raw.strip();

            if (line.startsWith("package ") && line.endsWith(";")) {
                return line.substring("package ".length(), line.length() - 1).strip();
            }
            if (line.startsWith("import ")
                    || line.startsWith("class ")
                    || line.startsWith("public ")
                    || line.startsWith("final ")
                    || line.startsWith("abstract ")) {
                return null;
            }
        }
        return null;
    }

    // ---------- запись ----------

    static void write(Path target, String source, boolean force) throws IOException {
        if (Files.exists(target) && !force) {
            throw new CliException("""
                    Файл уже есть: %s
                    Ваше решение не тронуто. Перезаписать текстом задачи:
                        hammerhall pull %s --force"""
                    .formatted(target.getFileName(),
                            target.getFileName().toString().replace(".java", "")));
        }

        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(target, source, StandardCharsets.UTF_8);
    }

    // ---------- вывод ----------

    private void report(String response, String taskId, Path relativePath) {
        String name = Json.string(response, "displayName");
        String stage = Json.string(response, "stage");
        String leadAxis = Json.string(response, "leadAxis");

        console.success(name == null || name.isBlank()
                ? taskId
                : taskId + " — " + name);
        console.print("  " + relativePath);

        StringBuilder about = new StringBuilder();
        if (stage != null && !stage.isBlank()) {
            about.append("ступень ").append(stage);
        }
        if (leadAxis != null && !leadAxis.isBlank()) {
            if (!about.isEmpty()) {
                about.append(" · ");
            }
            about.append("ведущая ось ").append(leadAxis);
        }
        if (!about.isEmpty()) {
            console.dim("  " + about);
        }

        console.print("");
        console.print("Решайте и отправляйте:");
        console.print("  hammerhall submit " + taskId + ".java");
    }
}
