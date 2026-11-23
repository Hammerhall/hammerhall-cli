package io.hammerhall.cli.command;

import io.hammerhall.cli.Args;
import io.hammerhall.cli.Context;
import io.hammerhall.cli.Msg;

/**
 * Выйти: удалить ключ этой машины.
 *
 * <p>До 0.2.0 выйти можно было только руками — удалив файл (ревью CLI №1,
 * СР-14). Честно про то, что команда делает: ключ удаляется С ЭТОЙ МАШИНЫ,
 * на платформе он остаётся действующим — отозвать поштучно пока нечем,
 * все ключи разом отзывает смена пароля. Это сказано в выводе.
 */
public final class LogoutCommand implements Command {

    @Override
    public String name() {
        return "logout";
    }

    @Override
    public int run(Args args, Context context) {
        args.requireCount(0, 0, "hammerhall logout");
        if (context.credentials().remove(context.platform())) {
            context.console().success(Msg.t("logout.done"));
            context.console().dim(Msg.t("logout.note"));
        } else {
            context.console().print(Msg.t("logout.none"));
        }
        return 0;
    }
}
