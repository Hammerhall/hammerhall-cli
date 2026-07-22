package io.hammerhall.cli;

import io.hammerhall.cli.command.LoginCommand;
import io.hammerhall.cli.command.ProfileCommand;
import io.hammerhall.cli.command.SubmitCommand;

import java.io.PrintStream;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;

/**
 * Точка входа клиента.
 *
 * <h3>Что это такое</h3>
 *
 * Курьер между папкой пользователя и платформой. Читает файл, отправляет
 * текст, печатает ответ. Больше ничего.
 *
 * <p>Осознанно НЕ умеет: считать очки, проверять решения, знать формулы.
 * Даже строку «SEQ 3→4» формирует сервер — клиент печатает как есть.
 * Причина практическая: поменяется формула очков, и не придётся обновлять
 * программу у всех, у кого она стоит.
 *
 * <h3>Живёт внутри кузницы</h3>
 *
 * Не устанавливается отдельно: клонировал репозиторий — клиент уже здесь.
 * Отсюда три удобства даром — ставить нечего, корень проекта известен
 * (это корень репозитория), конфиг лежит рядом.
 */
public final class Main {

    private static final String USAGE = """
            Hammerhall — клиент кузницы

              hammerhall login              сохранить токен доступа
              hammerhall submit <файл>      отправить решение на проверку
              hammerhall profile [кузница]  показать профиль

            Токен выдаётся при регистрации на сайте и показывается ОДИН раз.
            Потерял — перевыпусти в личном кабинете.
            """;

    private Main() {
    }

    public static void main(String[] args) {
        // Windows по умолчанию отдаёт cp1251, и кириллица в выводе
        // превращается в кашу. Терминал у пользователя мы не чиним,
        // но свой вывод обязаны отдать в UTF-8.
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);

        if (args.length == 0) {
            out.print(USAGE);
            System.exit(1);
        }

        Console console = new Console(out, err);

        try {
            int code = run(args, console);
            System.exit(code);
        } catch (CliException e) {
            // Ожидаемая ошибка: нет токена, файл не найден, сервер отказал.
            // Печатаем по-человечески, без стектрейса — он тут ничего
            // не объясняет и только пугает.
            console.error(e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            // Неожиданная. Стектрейс печатаем: это баг клиента, и он
            // нужен целиком, чтобы починить.
            console.error("Непредвиденная ошибка: " + e);
            e.printStackTrace(err);
            System.exit(2);
        }
    }

    private static int run(String[] args, Console console) throws Exception {
        String command = args[0];
        String[] rest = Arrays.copyOfRange(args, 1, args.length);

        return switch (command) {
            case "login" -> new LoginCommand(console).run(rest);
            case "submit" -> new SubmitCommand(console).run(rest);
            case "profile" -> new ProfileCommand(console).run(rest);
            case "help", "--help", "-h" -> {
                console.print(USAGE);
                yield 0;
            }
            default -> {
                console.error("Неизвестная команда: " + command);
                console.print(USAGE);
                yield 1;
            }
        };
    }
}
