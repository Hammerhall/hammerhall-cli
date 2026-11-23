package io.hammerhall.cli.command;

import io.hammerhall.cli.Args;
import io.hammerhall.cli.Context;

import java.util.Set;

/**
 * Команда клиента.
 *
 * <h3>Зачем общий вид (0.2.0)</h3>
 *
 * До 0.2.0 у четырёх команд не было общего интерфейса: сигнатуры разъехались
 * (две бросали {@code IOException}, две нет), каждая сама искала кузницу,
 * читала конфиг и создавала клиент HTTP внутри метода, — и потому ни одну
 * нельзя было проверить целиком (ревью CLI №1, АРХ-2). Теперь всё внешнее
 * приходит в {@link Context}, флаги разобраны заранее ({@link Args}), а
 * команда возвращает код выхода.
 */
public interface Command {

    /** Имя в командной строке. */
    String name();

    /** Флаги команды, кроме глобальных. */
    default Set<String> flags() {
        return Set.of();
    }

    /** Выполнить; вернуть код выхода. Ожидаемые отказы — {@code CliException}. */
    int run(Args args, Context context) throws Exception;
}
