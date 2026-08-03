package io.hammerhall.cli.command;

import io.hammerhall.cli.ApiClient;
import io.hammerhall.cli.CliException;
import io.hammerhall.cli.Config;
import io.hammerhall.cli.Console;
import io.hammerhall.cli.ForgeRoot;
import io.hammerhall.cli.Json;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * Получить ключ доступа и сохранить его.
 *
 * <h3>🔑 Почему команда спрашивает почту и пароль</h3>
 *
 * До 03.08 она просила вставить ключ — а взять его было негде. Регистрация
 * ключ создавала, но сырую строку тут же теряла: в базе оставался хеш,
 * не соответствующий ничему предъявляемому. Единственная выдающая ручка
 * требовала уже имеющегося доступа, а кнопки в кабинете не было. Ученик
 * доходил до {@code hammerhall login} и упирался.
 *
 * <p>Теперь клиент меняет почту и пароль на ключ сам
 * ({@code POST /auth/token}). Человек ключа не видит никогда: клиент
 * забирает его, сохраняет и о пароле забывает.
 *
 * <h3>Пароль звучит ОДИН раз и никуда не сохраняется</h3>
 *
 * Хранить его в {@code .hammerhall/config} нельзя по двум причинам сразу:
 * файл уезжает в форк ученика, а bcrypt считался бы на каждый {@code pull}.
 *
 * <p>⚠️ Честно про память: пароль читается в {@code char[]} и затирается
 * сразу после сборки запроса, но по дороге он неизбежно становится
 * {@link String} — тело запроса строкой и отправляется. Эта копия живёт
 * до сборки мусора. Процесс существует секунду, поэтому размен принят
 * сознательно; изображать полную зачистку было бы театром.
 *
 * <h3>Каждый вызов выпускает НОВЫЙ ключ, прежние продолжают работать</h3>
 *
 * Ради этого ключи и переезжали из колонки в таблицу: вход на второй
 * машине больше не отключает первую. Обратная сторона — неудачная попытка
 * или лишний прогон оставляют на сервере ключ, которым никто не пользуется.
 * Отозвать его поштучно сегодня нечем, поэтому команда не предлагает
 * «попробуйте ещё раз» там, где повтор ничего не исправит.
 *
 * <p>🔑 Смена пароля отзывает ВСЕ ключи разом — после неё {@code login}
 * нужен на каждой машине. Это единое правило платформы, а не особенность
 * клиента.
 *
 * <h3>Ключ аргументом больше не принимается</h3>
 *
 * Раньше можно было написать {@code hammerhall login <ключ>}. Убрано:
 * аргумент командной строки остаётся в истории терминала и виден в списке
 * процессов, то есть ровно тот секрет, который мы прячем от {@code git},
 * утекал в другое место. Вставить ключ из кабинета по-прежнему можно —
 * {@code hammerhall login --token}, — но ввод будет скрытым.
 */
public class LoginCommand {

    /** Печатаемый ASCII без пробелов — всё, что допустимо в значении заголовка. */
    private static final Pattern PRINTABLE_ASCII = Pattern.compile("[\\x21-\\x7E]+");

    /** Явная просьба вставить готовый ключ вместо входа по паролю. */
    private static final String PASTE_FLAG = "--token";

    private final Console console;

    /**
     * Один читатель на всю команду.
     *
     * <p>⚠️ Ровно поэтому он поле, а не локальная переменная в
     * try-with-resources: закрытие {@link BufferedReader} закрывает
     * {@link System#in}, и следующий вопрос читать было бы уже не из чего.
     * Раньше чтение было одно и это не стреляло; теперь их два.
     */
    private BufferedReader reader;

    public LoginCommand(Console console) {
        this.console = console;
    }

    public int run(String[] args) {
        Path root = ForgeRoot.find();
        Config config = Config.load(root);

        String token = pasteRequested(args)
                ? normalize(askSecret("Ключ: ", "Ключ выпускается в личном кабинете на hammerhall.io."))
                : login(config.api());

        // Проверка ДО сохранения, и она нужна обеим ветвям.
        //
        // Вставленный ключ может быть обрезан при копировании — это старая
        // причина. Полученный от платформы может быть прочитан неверно нами:
        // клиент разбирает JSON по именам полей, и переименование поля на
        // сервере даёт не ошибку, а тихий null. Такое в проекте уже было —
        // profile показывал нули при трёх решённых задачах, потому что поля
        // назывались tasksSolved вместо solvedTasks.
        verify(config.api(), token);

        config.saveToken(token);
        config.warnIfTracked(console);

        console.success("Ключ получен и сохранён");
        console.dim("  " + root.resolve(".hammerhall/config"));
        return 0;
    }

    /**
     * Просили ли вставить готовый ключ.
     *
     * <p>Аргумент, не равный флагу, — это почти наверняка попытка передать
     * ключ по-старому. Молча принять её нельзя (секрет уедет в историю
     * терминала), молча проигнорировать — тем более: человек решит, что
     * вошёл.
     */
    private boolean pasteRequested(String[] args) {
        if (args.length == 0) {
            return false;
        }
        if (PASTE_FLAG.equals(args[0])) {
            return true;
        }
        throw new CliException("""
                Ключ аргументом больше не принимается: он остался бы в истории
                терминала и был бы виден в списке процессов.

                    hammerhall login            вход по почте и паролю
                    hammerhall login --token    вставить ключ из кабинета""");
    }

    /**
     * Спросить почту и пароль, обменять их на ключ.
     *
     * <p>Ввод и обмен разведены намеренно: {@link #login(String, String, String)}
     * не трогает консоль и потому проверяется тестом против настоящего
     * сервера, а здесь остаётся только разговор с человеком.
     */
    private String login(String api) {
        console.print("Вход по той же почте и паролю, что и на сайте hammerhall.io.");

        String email = askEmail();
        char[] password = askPasswordChars();
        try {
            return login(api, email, new String(password));
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /**
     * Обменять почту и пароль на ключ.
     *
     * <p>Пакетная видимость — ради теста: здесь живёт всё, что ломается
     * молча, то есть форма запроса, имя поля в ответе и перевод кодов
     * отказа в слова.
     *
     * <p>⚠️ Проверять формат почты на клиенте НЕ надо. Правила валидации
     * живут на платформе, и второй их экземпляр здесь однажды разойдётся
     * с первым — форма начнёт отвергать адрес, который сервер принял бы.
     * Пустоту отбиваем, потому что пустой запрос не стоит отправлять.
     */
    String login(String api, String email, String password) {
        if (email == null || email.isBlank()) {
            throw new CliException("Почта не введена");
        }
        if (password == null || password.isEmpty()) {
            throw new CliException("Пароль не введён");
        }

        String body = "{\"email\":\"%s\",\"password\":\"%s\"}"
                .formatted(Json.escape(email.trim()), Json.escape(password));

        try {
            // Клиент БЕЗ ключа: на этом шаге его ещё нет.
            return extractKey(new ApiClient(api).post("/auth/token", body));
        } catch (CliException e) {
            throw explain(e);
        }
    }

    /**
     * Достать ключ из ответа платформы.
     *
     * <p>Имя поля — КОНТРАКТ ПЛАТФОРМЫ, а не наше предпочтение:
     * {@code TokenResponse} отдаёт {@code apiToken}. Ошибись мы в имени,
     * {@link Json#string} вернул бы {@code null} — не ошибку, а тишину,
     * и в конфиг уехала бы пустота.
     */
    static String extractKey(String response) {
        String token = Json.string(response, "apiToken");
        if (token == null || token.isBlank()) {
            throw new CliException("""
                    Платформа ответила успехом, но ключа в ответе нет.
                    Похоже, клиент устарел — обновите его и попробуйте снова.""");
        }
        String trimmed = token.trim();
        if (!PRINTABLE_ASCII.matcher(trimmed).matches()) {
            // Не должно случаться: ключ платформы — hex от SHA-256. Но если
            // случится, человек обязан прочитать фразу, а не стектрейс
            // IllegalArgumentException из HttpClient на следующем же запросе.
            throw new CliException("Платформа вернула ключ в неожиданном виде — обновите клиент.");
        }
        return trimmed;
    }

    /**
     * Перевести отказ платформы в слова, верные ДЛЯ ЭТОЙ ручки.
     *
     * <p>{@link ApiClient} переводит коды сам, и для трёх команд из четырёх
     * это правильно. Здесь смысл другой: {@code 401} значит не «ключ
     * устарел», а «не подошли почта или пароль» — ключа ещё нет, и совет
     * перевыпустить его отправил бы человека чинить исправное.
     */
    private CliException explain(CliException failure) {
        return switch (failure.status()) {
            case 401 -> new CliException("""
                    Не подошли почта или пароль.

                    Платформа намеренно не уточняет, что именно не подошло:
                    иначе по ответу можно было бы узнать, кто зарегистрирован.
                    Пароль забыт — «Забыли пароль?» на hammerhall.io.""", 401);
            case 403 -> new CliException("""
                    Почта не подтверждена.

                    Откройте ссылку из письма, пришедшего при регистрации,
                    и повторите. Письмо не пришло или потерялось — запросите
                    новое на hammerhall.io.""", 403);
            default -> failure;
        };
    }

    /**
     * Проверить, что ключ рабочий.
     *
     * <p>Успех — это отсутствие исключения: {@link ApiClient} превращает
     * отказ в понятное сообщение и до сюда не доходит. Тело ответа
     * не разбираем — {@code GET /profile} отдаёт сводку по кузницам,
     * и нам от него нужен только сам факт, что платформа пустила.
     */
    private void verify(String api, String token) {
        new ApiClient(api, token).get("/profile");
    }

    /**
     * Привести введённое к виду, пригодному для отправки.
     *
     * <p>Пробелы по краям обрезаются молча: это частый результат копирования
     * из браузера, и человек не виноват, что выделилось с запасом.
     *
     * <p>⚠️ Проверка на ASCII — не придирчивость. Ключ уезжает в заголовок
     * {@code Authorization}, а значения HTTP-заголовков обязаны быть ASCII:
     * на кириллице {@code HttpClient} бросает {@code IllegalArgumentException},
     * и человек вместо внятного «ключ выглядит неправильно» получает
     * стектрейс как при поломке платформы.
     *
     * <p>🔑 На ПАРОЛЬ это правило не распространяется и распространяться
     * не должно: пароль уезжает в теле запроса, в UTF-8, и кириллица там
     * законна. Перепутать эти два случая — значит запретить людям половину
     * возможных паролей без всякой причины.
     */
    static String normalize(String raw) {
        if (raw == null) {
            throw new CliException("Пустой ключ");
        }
        String token = raw.trim();
        if (token.isBlank()) {
            throw new CliException("Пустой ключ");
        }
        if (!PRINTABLE_ASCII.matcher(token).matches()) {
            throw new CliException("""
                    Ключ выглядит неправильно: в нём есть символы, которых в ключе не бывает.
                    Ключ платформы — строка из латинских букв и цифр.
                    Скорее всего скопировалось лишнее — попробуйте ещё раз.""");
        }
        return token;
    }

    /** Почта — не секрет, ввод показываем. */
    private String askEmail() {
        console.print("Почта: ");
        String line = readLine();
        return line == null ? "" : line;
    }

    /** Пароль скрытым вводом. */
    private char[] askPasswordChars() {
        String entered = askSecret("Пароль: ", null);
        return entered.toCharArray();
    }

    /**
     * Спросить то, что не должно быть видно.
     *
     * <p>{@link System#console()} скрывает ввод — секрет не останется
     * в истории терминала и не будет виден через плечо. Консоли нет
     * (запуск из IDE, вывод в конвейер) — читаем обычным потоком, но
     * говорим об этом вслух: человек должен знать, что набранное видно.
     */
    private String askSecret(String prompt, String hint) {
        if (hint != null) {
            console.print(hint);
        }

        var systemConsole = System.console();
        if (systemConsole != null) {
            char[] input = systemConsole.readPassword(prompt);
            return input == null ? "" : new String(input);
        }

        console.warn("Ввод не скрывается: консоль недоступна.");
        console.print(prompt);
        String line = readLine();
        return line == null ? "" : line;
    }

    /**
     * Прочитать строку, НЕ закрывая {@link System#in}.
     *
     * <p>Читатель создаётся один раз: закрой мы его после первого вопроса,
     * второй читать было бы не из чего.
     */
    private String readLine() {
        if (reader == null) {
            reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        }
        try {
            return reader.readLine();
        } catch (IOException e) {
            throw new CliException("Не удалось прочитать ввод", e);
        }
    }
}
