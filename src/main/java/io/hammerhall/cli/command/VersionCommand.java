package io.hammerhall.cli.command;

import io.hammerhall.cli.Args;
import io.hammerhall.cli.Context;
import io.hammerhall.cli.Msg;

/**
 * Версия клиента — первый вопрос поддержки (ревью CLI №1, БЛОК-2).
 *
 * <p>Печатает и окружение: Java, систему, адрес платформы. Это ровно то,
 * что спросят следом, когда «не работает».
 */
public final class VersionCommand implements Command {

    @Override
    public String name() {
        return "version";
    }

    @Override
    public int run(Args args, Context context) {
        args.requireCount(0, 0, "hammerhall version");
        context.console().print("hammerhall-cli " + context.version());
        context.console().dim(Msg.t("version.details",
                System.getProperty("java.version"),
                System.getProperty("os.name") + " " + System.getProperty("os.version"),
                context.platform()));
        return 0;
    }
}
