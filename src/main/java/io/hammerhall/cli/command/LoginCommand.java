package io.hammerhall.cli.command;

import io.hammerhall.cli.ApiClient;
import io.hammerhall.cli.Args;
import io.hammerhall.cli.CliException;
import io.hammerhall.cli.Console;
import io.hammerhall.cli.Context;
import io.hammerhall.cli.Credentials;
import io.hammerhall.cli.Input;
import io.hammerhall.cli.Json;
import io.hammerhall.cli.Msg;

import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Получить ключ доступа и сохранить его.
 *
 * <h3>🔑 Почему команда спрашивает почту и пароль</h3>
 *
 * Клиент меняет почту и пароль на ключ сам ({@code POST /auth/token}) —
 * человек ключа не видит никогда. Пароль звучит один раз и никуда
 * не сохраняется. ⚠️ Честно про память: пароль читается в {@code char[]}
 * и затирается, но тело запроса неизбежно становится строкой — эта копия
 * живёт до сборки мусора; процесс живёт секунду, размен принят сознательно.
 *
 * <h3>Каждый вызов выпускает НОВЫЙ ключ, прежние продолжают работать</h3>
 *
 * Вход на второй машине не отключает первую. Обратная сторона — лишний
 * прогон оставляет на сервере ключ, которым никто не пользуется: отозвать
 * его поштучно пока нечем. Смена пароля отзывает ВСЕ ключи разом.
 *
 * <h3>🔴 Сначала сохранить, потом проверять (0.2.0)</h3>
 *
 * До 0.2.0 полученный ключ сначала проверялся запросом {@code GET /profile},
 * а сохранялся только после. Ответь {@code /profile} отказом по любой
 * причине — задержка, сбой сервиса профиля, — и свежий рабочий ключ не
 * сохранялся, а человек видел «выполните hammerhall login» — то, что только
 * что выполнил. Три попытки — три мёртвых ключа на сервере и ноль на диске
 * (ревью CLI №1, ВЫС-8). Теперь проверяется ФОРМА ответа — поле есть, ключ
 * печатаемый, — ключ сохраняется, и только потом, необязательным шагом,
 * спрашивается {@code /profile}; неудача там — жёлтое предупреждение,
 * а не потеря ключа.
 *
 * <h3>Ключ аргументом не принимается</h3>
 *
 * Аргумент остаётся в истории терминала и виден в списке процессов.
 * Вставить ключ из кабинета — {@code hammerhall login --token}, ввод скрытый.
 */
public final class LoginCommand implements Command {

    /** Печатаемый ASCII без пробелов — всё, что допустимо в значении заголовка. */
    private static final Pattern PRINTABLE_ASCII = Pattern.compile("[\\x21-\\x7E]+");

    @Override
    public String name() {
        return "login";
    }

    @Override
    public Set<String> flags() {
        return Set.of("--token");
    }

    @Override
    public int run(Args args, Context context) {
        if (!args.rest().isEmpty()) {
            throw new CliException(Msg.t("login.arg.rejected"));
        }
        Console console = context.console();

        String key = args.has("--token") ? pasted(context) : exchange(context);

        // Ключи этой машины — до сохранения: ключ, оставшийся в кузнице
        // от 0.1.x, переезжает или удаляется, и новый ляжет поверх.
        Credentials credentials = context.credentials();
        credentials.save(context.platform(), key);
        console.success(Msg.t("login.saved"));
        console.dim("  " + credentials.file());

        verify(context, key);
        return 0;
    }

    /** Спросить почту и пароль, обменять их на ключ. */
    private String exchange(Context context) {
        Console console = context.console();
        Input input = context.input();
        console.print(Msg.t("login.intro"));
        if (!input.hidden()) {
            console.warn(Msg.t("login.visible"));
        }

        console.prompt(Msg.t("login.email"));
        String email = input.line();
        if (email == null || email.isBlank()) {
            throw new CliException(Msg.t("login.email.empty"));
        }
        char[] password = input.secret(Msg.t("login.password"));
        try {
            if (password.length == 0) {
                throw new CliException(Msg.t("login.password.empty"));
            }
            // ⚠️ Формат почты на клиенте НЕ проверяется: правила живут на
            // платформе, и второй их экземпляр здесь однажды разойдётся с первым.
            String body = Json.object("email", email.trim(), "password", new String(password));
            try (ApiClient api = context.anonymous()) {
                return extractKey(api.post(body, ApiClient.DEFAULT_TIMEOUT, "auth", "token").body());
            } catch (CliException e) {
                throw explain(e);
            }
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /** Вставить готовый ключ из кабинета — скрытым вводом. */
    private String pasted(Context context) {
        Console console = context.console();
        console.print(Msg.t("login.token.hint"));
        if (!context.input().hidden()) {
            console.warn(Msg.t("login.visible"));
        }
        char[] typed = context.input().secret(Msg.t("login.token.prompt"));
        try {
            return normalize(new String(typed));
        } finally {
            Arrays.fill(typed, '\0');
        }
    }

    /**
     * Достать ключ из ответа. Имя поля — контракт платформы
     * ({@code TokenResponse.apiToken}); нет поля — не тишина, а ошибка.
     */
    static String extractKey(Json.Node response) {
        String token = response.get("apiToken").text();
        if (token == null) {
            throw new CliException(Msg.t("login.nokey"));
        }
        String trimmed = token.trim();
        if (!PRINTABLE_ASCII.matcher(trimmed).matches()) {
            throw new CliException(Msg.t("login.badkey"));
        }
        return trimmed;
    }

    /**
     * Отказ ручки входа — своими словами: здесь {@code 401} значит не
     * «ключ устарел», а «не подошли почта или пароль».
     */
    private static CliException explain(CliException failure) {
        return switch (failure.status()) {
            case 401 -> new CliException(Msg.t("login.401"), 401);
            case 403 -> new CliException(Msg.t("login.403"), 403);
            default -> failure;
        };
    }

    /** Проверить сохранённый ключ. Не вышло — предупредить, ключ не трогать. */
    private static void verify(Context context, String key) {
        try (ApiClient api = context.withToken(key)) {
            api.get("profile");
        } catch (CliException e) {
            context.console().warn(e.status() == 401
                    ? Msg.t("login.verify.rejected")
                    : Msg.t("login.verify.failed", firstLine(e.getMessage())));
        }
    }

    /**
     * Ключ из буфера обмена — к виду, пригодному для заголовка.
     *
     * <p>Пробелы по краям — результат копирования, обрезаются молча.
     * ⚠️ ASCII обязателен: ключ уезжает в заголовок {@code Authorization},
     * а кириллица там даёт {@code IllegalArgumentException} вместо внятного
     * «ключ выглядит неправильно». На ПАРОЛЬ правило не распространяется:
     * он уезжает в теле, в UTF-8, и кириллица там законна.
     */
    static String normalize(String raw) {
        String token = raw == null ? "" : raw.trim();
        if (token.isEmpty()) {
            throw new CliException(Msg.t("login.token.empty"));
        }
        if (!PRINTABLE_ASCII.matcher(token).matches()) {
            throw new CliException(Msg.t("login.token.bad"));
        }
        return token;
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        int nl = text.indexOf('\n');
        return nl < 0 ? text : text.substring(0, nl);
    }
}
