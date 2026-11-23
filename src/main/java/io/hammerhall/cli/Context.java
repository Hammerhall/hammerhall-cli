package io.hammerhall.cli;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

/**
 * Всё, что нужно команде снаружи: вывод, ввод, каталоги, окружение,
 * часы, адрес платформы, ключ, клиент HTTP.
 *
 * <h3>Зачем (0.2.0)</h3>
 *
 * До 0.2.0 каждая команда сама искала корень кузницы, читала конфиг
 * и создавала {@link ApiClient} внутри метода. Проверить команду целиком
 * было нельзя, и из неё вырезали кусочки «с пакетной видимостью ради теста»
 * — шесть раз по javadoc; {@code Main} был покрыт на 0 %, вход по паролю —
 * на 43 % (ревью CLI №1, АРХ-2). Теперь контекст собирается один раз —
 * в {@code Main} из настоящего мира, в тесте — из {@code @TempDir}, поддельного
 * ввода и настоящего {@code HttpServer}, — и команды проверяются сквозняком,
 * от аргументов до кода возврата, без единого мока.
 */
public final class Context {

    private final Console console;
    private final Input input;
    private final Path workDir;
    private final Path home;
    private final Map<String, String> env;
    private final Clock clock;
    private final ApiClient.Sleeper sleeper;
    private final String version;
    private final String apiFlag;
    private final boolean verbose;

    private boolean forgeSearched;
    private ForgeRoot.Found forge;
    private Platform platform;
    private Credentials credentials;

    public Context(Console console, Input input, Path workDir, Path home, Map<String, String> env,
                   Clock clock, ApiClient.Sleeper sleeper, String version, String apiFlag, boolean verbose) {
        this.console = console;
        this.input = input;
        this.workDir = workDir;
        this.home = home;
        this.env = env;
        this.clock = clock;
        this.sleeper = sleeper;
        this.version = version;
        this.apiFlag = apiFlag;
        this.verbose = verbose;
    }

    public Console console() {
        return console;
    }

    public Input input() {
        return input;
    }

    public Clock clock() {
        return clock;
    }

    public ApiClient.Sleeper sleeper() {
        return sleeper;
    }

    public String version() {
        return version;
    }

    public boolean verbose() {
        return verbose;
    }

    public Path home() {
        return home;
    }

    /** Каталог, из которого запущена команда: относительные пути — от него. */
    public Path workDir() {
        return workDir;
    }

    /** Кузница, в которой запущена команда; не в кузнице — понятная ошибка. */
    public ForgeRoot.Found forge() {
        ForgeRoot.Found found = forgeOrNull();
        if (found == null) {
            throw new CliException(Msg.t("forge.notfound"));
        }
        return found;
    }

    /** Кузница или {@code null} — для команд, которым она не обязательна. */
    public ForgeRoot.Found forgeOrNull() {
        if (!forgeSearched) {
            forgeSearched = true;
            forge = ForgeRoot.search(workDir, home);
            if (forge != null && forge.guessed()) {
                console.warnOnce("forge.guessed", Msg.t("forge.guessed", forge.id()));
            }
        }
        return forge;
    }

    /** Адрес платформы: по умолчанию, либо {@code --api}, либо {@code HAMMERHALL_API}. */
    public Platform platform() {
        if (platform == null) {
            ForgeRoot.Found here = forgeOrNull();
            platform = Platform.resolve(apiFlag, env.get("HAMMERHALL_API"),
                    Credentials.legacyApi(here == null ? null : here.root()), console);
        }
        return platform;
    }

    /** Ключи этой машины; ключ, оставшийся в кузнице от 0.1.x, переезжает сюда. */
    public Credentials credentials() {
        if (credentials == null) {
            platform();   // сначала адрес: строка api= старого файла — до его удаления
            credentials = Credentials.load(home);
            ForgeRoot.Found here = forgeOrNull();
            credentials.migrateFrom(here == null ? null : here.root(), console);
        }
        return credentials;
    }

    /** Клиент без ключа — только для обмена почты и пароля на ключ. */
    public ApiClient anonymous() {
        return new ApiClient(platform(), null, this);
    }

    /** Клиент с ключом этой машины; ключа нет — «выполните вход». */
    public ApiClient authorized() {
        return new ApiClient(platform(), credentials().require(platform()), this);
    }

    /** Клиент с данным ключом — проверить только что полученный. */
    public ApiClient withToken(String token) {
        return new ApiClient(platform(), token, this);
    }
}
