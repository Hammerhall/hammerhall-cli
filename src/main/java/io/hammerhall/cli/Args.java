package io.hammerhall.cli;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Разбор командной строки — один раз и одинаково для всех команд.
 *
 * <h3>Зачем (0.2.0)</h3>
 *
 * До 0.2.0 каждая команда разбирала {@code String[]} по-своему, и четыре
 * команды жили по четырём правилам: {@code pull --force MovCode0002}
 * принимал флаг за номер задачи, {@code profile --help} уходил запросом
 * {@code GET /profile/--help}, {@code login --token лишнее} молча глотал
 * лишнее (ревью CLI №1, СР-4). Теперь флаги отделяются от позиционных
 * аргументов здесь, в любом месте строки, а неизвестный флаг — ошибка
 * с подсказкой, а не запрос на сервер.
 *
 * <h3>Глобальные флаги</h3>
 *
 * {@code --api <адрес>} (или {@code --api=адрес}), {@code --no-color},
 * {@code --verbose}, {@code --version}, {@code -h}/{@code --help} — понимаются
 * в любом месте строки. Остальные флаги — команды; какие она понимает,
 * она объявляет сама. {@code --} — дальше флагов нет, всё позиционное
 * (файл, названный с дефиса, всё ещё можно отправить).
 */
public final class Args {

    /** Флаги, понятные любой команде. */
    static final Set<String> GLOBAL = Set.of("--no-color", "--verbose", "--version", "--help", "-h");

    /** Глобальные флаги со значением. */
    static final Set<String> GLOBAL_VALUED = Set.of("--api");

    private final List<String> positional;
    private final Map<String, String> flags;

    private Args(List<String> positional, Map<String, String> flags) {
        this.positional = positional;
        this.flags = flags;
    }

    /**
     * Разобрать строку. Проверка «знает ли команда этот флаг» — отдельно,
     * в {@link #requireOnly}: на этом шаге команда ещё не известна.
     */
    public static Args parse(String[] raw) {
        List<String> positional = new ArrayList<>();
        Map<String, String> flags = new LinkedHashMap<>();
        boolean onlyPositional = false;

        for (int i = 0; i < raw.length; i++) {
            String arg = raw[i];
            if (onlyPositional || !arg.startsWith("-") || arg.equals("-")) {
                positional.add(arg);
                continue;
            }
            if (arg.equals("--")) {
                onlyPositional = true;
                continue;
            }
            int eq = arg.indexOf('=');
            String name = eq > 0 ? arg.substring(0, eq) : arg;
            if (GLOBAL_VALUED.contains(name)) {
                String value;
                if (eq > 0) {
                    value = arg.substring(eq + 1);
                } else if (i + 1 < raw.length) {
                    value = raw[++i];
                } else {
                    throw new CliException(Msg.t("args.value.missing", name));
                }
                flags.put(name, value);
                continue;
            }
            if (eq > 0) {
                throw new CliException(Msg.t("args.value.unexpected", name));
            }
            flags.put(name, "");
        }
        return new Args(Collections.unmodifiableList(positional), Collections.unmodifiableMap(flags));
    }

    /** Позиционные аргументы; первый — имя команды. */
    public List<String> positional() {
        return positional;
    }

    /** Позиционные аргументы после имени команды. */
    public List<String> rest() {
        return positional.isEmpty() ? List.of() : positional.subList(1, positional.size());
    }

    public boolean has(String flag) {
        return flags.containsKey(flag);
    }

    /** Значение флага со значением или {@code null}. */
    public String value(String flag) {
        return flags.get(flag);
    }

    /**
     * Неизвестный команде флаг — ошибка с подсказкой.
     *
     * @param command имя команды — для текста
     * @param own     флаги, которые понимает команда (без глобальных)
     */
    public void requireOnly(String command, Set<String> own) {
        for (String flag : flags.keySet()) {
            if (!GLOBAL.contains(flag) && !GLOBAL_VALUED.contains(flag) && !own.contains(flag)) {
                throw new CliException(Msg.t("args.flag.unknown", flag, command));
            }
        }
    }

    /**
     * Ровно столько позиционных аргументов после команды, сколько она ждёт.
     *
     * @param usage пример верного вызова — его и покажем
     */
    public void requireCount(int min, int max, String usage) {
        int count = rest().size();
        if (count < min) {
            throw new CliException(Msg.t("args.missing", usage));
        }
        if (count > max) {
            throw new CliException(Msg.t("args.extra", String.join(" ", rest().subList(max, count)), usage));
        }
    }
}
