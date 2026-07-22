package io.hammerhall.cli.command;

import io.hammerhall.cli.ApiClient;
import io.hammerhall.cli.CliException;
import io.hammerhall.cli.Config;
import io.hammerhall.cli.Console;
import io.hammerhall.cli.ForgeRoot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Сохранить токен доступа.
 *
 * <h3>Токен вводится руками — и это осознанно</h3>
 *
 * Клиент НЕ регистрирует пользователей. Регистрация живёт на сайте, где
 * есть подтверждение почты; открытый {@code POST /users} из терминала
 * означал бы аккаунты на {@code user@example.com} и невозможность
 * отличить живого человека от опечатки.
 *
 * <p>Токен показывается ОДИН раз при регистрации. Потерял — перевыпускай
 * в личном кабинете: в базе лежит SHA-256, показать сохранённый нельзя
 * даже при желании.
 *
 * <h3>Проверяем до сохранения</h3>
 *
 * Один запрос к платформе прежде чем писать в конфиг. Иначе человек
 * вставит обрезанный токен, увидит «сохранено», а потом получит отказ
 * на первом {@code submit} — и будет искать причину не там.
 */
public class LoginCommand {

    private final Console console;

    public LoginCommand(Console console) {
        this.console = console;
    }

    public int run(String[] args) {
        Path root = ForgeRoot.find();
        Config config = Config.load(root);

        String token = args.length > 0 ? args[0] : ask();
        if (token.isBlank()) {
            throw new CliException("Пустой токен");
        }

        // Пробелы по краям — частый результат копирования из браузера.
        // Молча обрезаем: человек не виноват, что выделилось с запасом.
        token = token.trim();

        verify(config.api(), token);

        config.saveToken(token);
        config.warnIfTracked(console);

        console.success("Токен принят и сохранён");
        console.dim("  " + root.resolve(".hammerhall/config"));
        return 0;
    }

    /**
     * Спросить токен.
     * <p>
     * {@link System#console()} скрывает ввод — токен не останется
     * в истории терминала и не будет виден через плечо. Если консоли нет
     * (запуск из IDE, вывод в конвейер), читаем обычным потоком: работать
     * должно и там, просто без сокрытия.
     */
    private String ask() {
        console.print("Токен выдаётся при регистрации на сайте и показывается один раз.");

        var systemConsole = System.console();
        if (systemConsole != null) {
            char[] input = systemConsole.readPassword("Токен: ");
            return input == null ? "" : new String(input);
        }

        console.print("Токен: ");
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            return line == null ? "" : line;
        } catch (IOException e) {
            throw new CliException("Не удалось прочитать ввод", e);
        }
    }

    /**
     * Проверить, что токен рабочий.
     * <p>
     * Успех — это отсутствие исключения: {@link ApiClient} превращает 401
     * в понятное сообщение и до сюда не доходит. Тело ответа не разбираем —
     * {@code GET /profile} отдаёт список кузниц, и нам от него нужен только
     * сам факт, что платформа пустила.
     */
    private void verify(String api, String token) {
        new ApiClient(api, token).get("/profile");
    }
}
