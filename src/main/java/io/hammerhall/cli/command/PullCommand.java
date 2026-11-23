package io.hammerhall.cli.command;

import io.hammerhall.cli.ApiClient;
import io.hammerhall.cli.Args;
import io.hammerhall.cli.CliException;
import io.hammerhall.cli.Console;
import io.hammerhall.cli.Context;
import io.hammerhall.cli.ForgeRoot;
import io.hammerhall.cli.Ids;
import io.hammerhall.cli.Json;
import io.hammerhall.cli.Msg;
import io.hammerhall.cli.SafeFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Забрать задачу из кузницы и положить файл в свой проект.
 *
 * <p>Закрывает сценарий «бесплатные кончились, оплатил, хочу ещё». Уровень
 * доступа проверяет платформа (на закрытую задачу — 404), квоту выдачи —
 * тоже она (429 с текстом — его клиент и печатает).
 *
 * <h3>Куда кладётся файл</h3>
 *
 * В пакет, объявленный в самом тексте задачи. Имя файла — номер задачи
 * в том виде, в каком его вернула ПЛАТФОРМА (поле {@code id}), а не в том,
 * какой набрал человек: {@code pull movcode0002} до 0.2.0 клал на диск
 * {@code movcode0002.java} с {@code public class MovCode0002} внутри —
 * файл не компилировался, и человек чинил не то (ревью CLI №1, СР-5).
 * По стандарту кузницы имя класса и есть номер задачи.
 *
 * <h3>Чужую работу не затираем</h3>
 *
 * Файл есть — команда отказывается. {@code --force} перезаписывает, но
 * прежний файл сначала откладывается рядом, {@code MovCode0002.java.bak}:
 * человек мог решать задачу неделю, и до 0.2.0 флаг стирал её без
 * возможности вернуть (СР-6). Запись — целиком или никак.
 */
public final class PullCommand implements Command {

    /** Часть пакета — обычный Java-идентификатор, и ничего больше. */
    private static final Pattern PACKAGE_PART = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    @Override
    public String name() {
        return "pull";
    }

    @Override
    public Set<String> flags() {
        return Set.of("--force");
    }

    @Override
    public int run(Args args, Context context) throws IOException {
        args.requireCount(1, 1, "hammerhall pull MovCode0002");
        String asked = Ids.task(stripJava(args.rest().getFirst()));
        ForgeRoot.Found forge = context.forge();
        Console console = context.console();

        console.dim(Msg.t("pull.taking", asked));
        Json.Node task;
        try (ApiClient api = context.authorized()) {
            task = api.get("tasks", forge.id(), asked).body();
        }

        String source = task.get("source").text();
        if (source == null) {
            // Задача в каталоге есть, а текста нет: артефакт выдачи ещё
            // не залит. Это не ошибка человека, и говорить надо так.
            throw new CliException(Msg.t("pull.nosource", asked));
        }
        String id = task.get("id").text();
        String taskId = id != null && Ids.TASK.matcher(id).matches() ? id : asked;

        Path target = targetPath(forge.root(), source, taskId);
        if (Files.exists(target)) {
            if (!args.has("--force")) {
                throw new CliException(Msg.t("pull.exists", ForgeRoot.shown(forge.root(), target), taskId));
            }
            Path backup = backupOf(target);
            Files.move(target, backup);
            console.warn(Msg.t("pull.backup", ForgeRoot.shown(forge.root(), backup)));
        }
        SafeFiles.write(target, source);

        report(console, task, taskId, ForgeRoot.shown(forge.root(), target));
        return 0;
    }

    /** Принимаем и {@code MovCode0002}, и {@code MovCode0002.java}. */
    static String stripJava(String argument) {
        return argument.endsWith(".java") ? argument.substring(0, argument.length() - ".java".length()) : argument;
    }

    /**
     * Путь файла: {@code src/main/java} + пакет из текста задачи + имя.
     * Пакета нет — корень исходников: файл человек получит, место поправит сам.
     */
    static Path targetPath(Path root, String source, String taskId) {
        Path dir = root.resolve("src/main/java");
        String declared = packageOf(source);
        if (declared != null) {
            // split с -1: без него пустые хвосты отбрасываются молча, и «io..»
            // превратился бы в невинный «io».
            for (String part : declared.split("\\.", -1)) {
                if (!PACKAGE_PART.matcher(part).matches()) {
                    // Пакет пришёл с сервера: «..» или слэш увели бы запись за пределы кузницы.
                    throw new CliException(Msg.t("pull.package.bad", declared));
                }
                dir = dir.resolve(part);
            }
        }
        return dir.resolve(taskId + ".java");
    }

    /**
     * Объявление пакета. Ищем до первого {@code import} или объявления
     * типа: слово «package» в комментарии ниже — запросто.
     */
    static String packageOf(String source) {
        for (String raw : source.lines().toList()) {
            String line = raw.strip();
            if (line.startsWith("package ") && line.endsWith(";")) {
                return line.substring("package ".length(), line.length() - 1).strip();
            }
            if (line.startsWith("import ") || line.startsWith("class ") || line.startsWith("public ")
                    || line.startsWith("final ") || line.startsWith("abstract ") || line.startsWith("void ")
                    || line.startsWith("record ") || line.startsWith("interface ") || line.startsWith("enum ")) {
                return null;
            }
        }
        return null;
    }

    /** {@code X.java.bak}, занято — {@code X.java.bak.1}, {@code .2}… Прежние отложенные не трогаем. */
    static Path backupOf(Path target) {
        Path backup = target.resolveSibling(target.getFileName() + ".bak");
        for (int n = 1; Files.exists(backup); n++) {
            backup = target.resolveSibling(target.getFileName() + ".bak." + n);
        }
        return backup;
    }

    /**
     * Что забрали и куда положили. Все три поля карточки необязательны,
     * и каждое отсутствующее меняет форму вывода: разделитель не должен
     * повиснуть в воздухе, а {@code null} — напечататься словом.
     */
    private static void report(Console console, Json.Node task, String taskId, String relativePath) {
        String name = task.get("displayName").text();
        String stage = task.get("stage").text();
        String leadAxis = task.get("leadAxis").text();

        console.success(name == null ? taskId : taskId + " — " + name);
        console.print("  " + relativePath);

        StringBuilder about = new StringBuilder();
        if (stage != null) {
            about.append(Msg.t("pull.stage", stage));
        }
        if (leadAxis != null) {
            if (!about.isEmpty()) {
                about.append(" · ");
            }
            about.append(Msg.t("pull.axis", leadAxis));
        }
        if (!about.isEmpty()) {
            console.dim("  " + about);
        }
        console.print("");
        console.print(Msg.t("pull.next", taskId));
    }
}
