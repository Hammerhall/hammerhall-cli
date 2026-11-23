package io.hammerhall.cli;

import io.hammerhall.cli.command.Command;
import io.hammerhall.cli.command.LoginCommand;
import io.hammerhall.cli.command.LogoutCommand;
import io.hammerhall.cli.command.ProfileCommand;
import io.hammerhall.cli.command.PullCommand;
import io.hammerhall.cli.command.SubmitCommand;
import io.hammerhall.cli.command.VersionCommand;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Точка входа клиента.
 *
 * <h3>Что это такое</h3>
 *
 * Курьер между папкой пользователя и платформой. Читает файл, отправляет
 * текст, печатает ответ. Осознанно НЕ умеет: считать очки, проверять
 * решения, знать формулы — поменяется формула, и не придётся обновлять
 * программу у всех, у кого она стоит.
 *
 * <h3>Коды возврата (0.2.0)</h3>
 *
 * 0 — зачтено (или команда выполнена); 1 — решение не зачтено; 2 — ошибка:
 * неверный вызов, файл, отказ платформы, непредвиденная ошибка клиента;
 * 3 — платформа недоступна или не ответила вовремя. До 0.2.0 у «не прошло»
 * и «нет связи» был один код 1, и скрипт не мог их различить (ревью CLI №1,
 * СР-14). Справка по {@code help} — в stdout с кодом 0; при неверном вызове —
 * в stderr с кодом 2: обычная конвенция.
 */
public final class Main {

    private static final List<Command> COMMANDS = List.of(
            new LoginCommand(), new LogoutCommand(), new PullCommand(), new SubmitCommand(),
            new ProfileCommand(), new VersionCommand());

    private static final Map<String, Command> BY_NAME = new LinkedHashMap<>();

    static {
        COMMANDS.forEach(command -> BY_NAME.put(command.name(), command));
    }

    private Main() {
    }

    public static void main(String[] raw) {
        // 🔴 Кодировка вывода на Windows: два взаимоисключающих случая.
        // Настоящая консоль — JDK кодирует её кодовой страницей сам, и правильно;
        // обёртка в UTF-8 рассыпала бы кириллицу. Труба (MinTTY, конвейер,
        // файл) — преобразования нет, и тут UTF-8 обязателен.
        // 🔑 Терминал от трубы отличает только isTerminal(): System.console()
        // с JDK 22 не null и при перенаправлении.
        java.io.Console jvmConsole = System.console();
        boolean terminal = jvmConsole != null && jvmConsole.isTerminal();
        PrintStream out = terminal ? System.out : new PrintStream(System.out, true, StandardCharsets.UTF_8);
        PrintStream err = terminal ? System.err : new PrintStream(System.err, true, StandardCharsets.UTF_8);

        Args args;
        try {
            args = Args.parse(raw);
        } catch (CliException e) {
            new Console(out, err, terminal, false).error(e.getMessage());
            System.exit(2);
            return;
        }

        String noColor = System.getenv("NO_COLOR");
        boolean colored = terminal && !args.has("--no-color") && (noColor == null || noColor.isEmpty());
        Console console = new Console(out, err, terminal, colored);
        Context context = new Context(console, Input.system(console, System.in),
                Path.of("").toAbsolutePath(), Path.of(System.getProperty("user.home")), System.getenv(),
                Clock.systemUTC(), duration -> Thread.sleep(duration),
                Version.current(), args.value("--api"), args.has("--verbose"));

        System.exit(execute(args, context));
    }

    /** Выполнить разобранную строку; вернуть код выхода. Сквозные тесты зовут отсюда. */
    public static int execute(Args args, Context context) {
        return execute(args, context, BY_NAME);
    }

    /** То же — с данным набором команд (тест подкладывает команду, которая ломается). */
    static int execute(Args args, Context context, Map<String, Command> commands) {
        Console console = context.console();
        try {
            if (args.has("--version")) {
                context.platform();
                return commands.get("version").run(args, context);
            }
            if (args.positional().isEmpty()) {
                if (args.has("--help") || args.has("-h")) {
                    console.block(Msg.t("main.usage"));
                    return 0;
                }
                console.blockErr(Msg.t("main.usage"));
                return 2;
            }
            String name = args.positional().getFirst();
            if (name.equals("help") || args.has("--help") || args.has("-h")) {
                console.block(Msg.t("main.usage"));
                return 0;
            }
            Command command = commands.get(name);
            if (command == null) {
                console.error(Msg.t("main.unknown", name));
                console.blockErr(Msg.t("main.usage"));
                return 2;
            }
            args.requireOnly(name, command.flags());
            // Адрес платформы — до работы команды: неверный --api ломает
            // вызов сразу, а «адрес переопределён» стоит первой строкой,
            // а не посреди диалога.
            context.platform();
            return command.run(args, context);
        } catch (CliException e) {
            // Ожидаемая ошибка: печатаем по-человечески, без стектрейса —
            // он тут ничего не объясняет и только пугает.
            console.error(e.getMessage());
            return e.kind().exitCode();
        } catch (Exception e) {
            // Неожиданная. Стектрейс печатаем: это баг клиента, и он нужен
            // целиком, чтобы починить.
            console.error(Msg.t("main.unexpected", e));
            console.stackTrace(e);
            return 2;
        }
    }
}
