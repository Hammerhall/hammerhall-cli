package io.hammerhall.cli;

import io.hammerhall.cli.command.LoginCommand;
import io.hammerhall.cli.command.ProfileCommand;
import io.hammerhall.cli.command.PullCommand;
import io.hammerhall.cli.command.SubmitCommand;

import java.io.PrintStream;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;

/**
 * Точка входа клиента.
 *
 * <h3>Что это такое</h3>
 * <p>
 * Курьер между папкой пользователя и платформой. Читает файл, отправляет
 * текст, печатает ответ. Больше ничего.
 *
 * <p>Осознанно НЕ умеет: считать очки, проверять решения, знать формулы.
 * Даже строку «SEQ 3→4» формирует сервер — клиент печатает как есть.
 * Причина практическая: поменяется формула очков, и не придётся обновлять
 * программу у всех, у кого она стоит.
 *
 * <h3>Живёт внутри кузницы</h3>
 * <p>
 * Не устанавливается отдельно: клонировал репозиторий — клиент уже здесь.
 * Отсюда три удобства даром — ставить нечего, корень проекта известен
 * (это корень репозитория), конфиг лежит рядом.
 */
public final class Main {

    static final String USAGE = """
            Hammerhall — клиент кузницы
            
              hammerhall login              войти по почте и паролю
              hammerhall login --token      вставить ключ из личного кабинета
              hammerhall pull <задача>      забрать задачу в свой проект
              hammerhall submit <файл>      отправить решение на проверку
              hammerhall profile [кузница]  показать профиль
            
            Почта и пароль — те же, что на сайте hammerhall.io. Ключ доступа
            клиент получает и хранит сам; видеть его вам не нужно.
            """;

    private Main() {
    }

    public static void main(String[] args) {
        // 🔴 Кодировка вывода на Windows: два взаимоисключающих случая.
        //
        // stdout привязан к НАСТОЯЩЕЙ консоли — JDK кодирует вывод её кодовой
        // страницей сам, и делает это правильно. Обернём в UTF-8 — отдадим
        // консоли готовые UTF-8-байты, а она прочтёт их своей страницей (866
        // на русской Windows), и кириллица рассыплется на ╨, ╤, Ш.
        //
        // stdout — ТРУБА (MinTTY, конвейер, файл) — консольного преобразования
        // нет, байты идут как есть. Здесь UTF-8 обязателен, иначе JDK возьмёт
        // native.encoding, то есть cp1251, а принимающая сторона почти всегда
        // ждёт UTF-8.
        //
        // Раньше обёртка стояла безусловно. Она чинила трубу и ломала консоль,
        // и потому у одних работало, у других нет — при одном и том же jar
        // и одинаково настроенном терминале.
        //
        // 🔑 System.console() != null для различения НЕ годится: с JDK 22
        // метод возвращает объект и при перенаправлении. Терминал от трубы
        // отличает только isTerminal().
        java.io.Console jvmConsole = System.console();
        boolean terminal = jvmConsole != null && jvmConsole.isTerminal();

        PrintStream out = terminal
                ? System.out
                : new PrintStream(System.out, true, StandardCharsets.UTF_8);
        PrintStream err = terminal
                ? System.err
                : new PrintStream(System.err, true, StandardCharsets.UTF_8);

        Console console = new Console(out, err, terminal);

        // ⚠️ Справка печатается ЧЕРЕЗ console, а не в поток напрямую.
        // Раньше здесь стоял out.print(USAGE), и она проходила мимо замены
        // непроходимых символов: в консоли Windows длинное тире становилось
        // вопросительным знаком — «Hammerhall ? клиент кузницы». Справка это
        // первое, что человек читает, и чинить её последней было ошибкой.
        if (args.length == 0) {
            console.block(USAGE);
            System.exit(1);
        }

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
            case "pull" -> new PullCommand(console).run(rest);
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
