package io.hammerhall.cli.command;

import io.hammerhall.cli.ApiClient;
import io.hammerhall.cli.Args;
import io.hammerhall.cli.Console;
import io.hammerhall.cli.Context;
import io.hammerhall.cli.ForgeRoot;
import io.hammerhall.cli.Ids;
import io.hammerhall.cli.Json;
import io.hammerhall.cli.Msg;

import java.util.List;

/**
 * Показать профиль: в кузнице — по осям, вне кузницы — список кузниц.
 *
 * <h3>Клиент ничего не считает</h3>
 *
 * Сервер присылает готовые числа, клиент рисует полоски. Поменяются веса,
 * порог фундамента или способ свёртки — обновлять программу у всех
 * не придётся.
 *
 * <h3>🔴 Потолок полоски — с сервера (0.2.0)</h3>
 *
 * До 0.2.0 здесь была зашита константа 12.5 — в классе, который начинался
 * словами «клиент ничего не считает». Это константа СТАНДАРТА КУЗНИЦЫ:
 * она поедет с ростом корпуса и у другой кузницы будет своя (ревью CLI №1,
 * СР-1). Теперь длина полоски — {@code fill} оси (0..1), если сервер его
 * прислал, иначе {@code score / maxScore} оси, иначе {@code axisMax}
 * профиля. Пока платформа их не отдаёт — запасное 12.5, и оно здесь
 * только до правки сервера (PR платформы следом за 0.2.0).
 *
 * <p>Числа — в {@link java.util.Locale#ROOT} (СР-3): «4.5», а не «4,5»;
 * фундамент меньше процента — «&lt;1%», а не честный, но лживый «0%».
 */
public final class ProfileCommand implements Command {

    /** Ширина полоски. Восемь осей должны помещаться в узкий терминал. */
    private static final int BAR_WIDTH = 20;

    /** Запасной потолок оси — пока платформа не присылает свой. */
    static final double FALLBACK_AXIS_MAX = 12.5;

    @Override
    public String name() {
        return "profile";
    }

    @Override
    public int run(Args args, Context context) {
        args.requireCount(0, 1, "hammerhall profile [кузница]");
        String forgeId;
        if (!args.rest().isEmpty()) {
            forgeId = Ids.forge(args.rest().getFirst());
        } else {
            ForgeRoot.Found here = context.forgeOrNull();
            forgeId = here == null ? null : here.id();
        }

        try (ApiClient api = context.authorized()) {
            if (forgeId == null) {
                printForges(context.console(), api.get("profile").body());
            } else {
                print(context.console(), forgeId, api.get("profile", forgeId).body());
            }
        }
        return 0;
    }

    /** Вне кузницы и без аргумента — список кузниц со сводкой (ответ — массив). */
    static void printForges(Console console, Json.Node forges) {
        List<Json.Node> items = forges.items();
        console.print("");
        if (items.isEmpty()) {
            console.dim(Msg.t("profile.forges.none"));
            return;
        }
        console.print(Msg.t("profile.forges.title"));
        for (Json.Node forge : items) {
            String name = forge.get("displayName").text();
            console.print(Msg.t("profile.forges.row",
                    forge.get("id").string(),
                    name == null ? "" : name,
                    forge.get("solvedTasks").asLong(0),
                    forge.get("submissions").asLong(0)));
        }
        console.print("");
        console.dim(Msg.t("profile.forges.hint"));
    }

    /** Профиль кузницы. Имена полей — контракт платформы ({@code ProfileResponse}). */
    static void print(Console console, String forgeId, Json.Node profile) {
        long solved = profile.get("solvedTasks").asLong(0);
        long points = profile.get("points").asLong(0);
        String forgeName = profile.get("forgeName").text();

        console.print("");
        console.print(Msg.t("profile.forge", forgeName == null ? forgeId : forgeName + " (" + forgeId + ")"));
        console.print(Msg.t("profile.totals", solved, points));
        console.print("");

        Json.Node axes = profile.get("axes");
        List<String> names = axes.keys();
        if (names.isEmpty()) {
            // Пустой вектор — не ошибка, но причины у него две, и путать их нельзя:
            // у слоя code разметки осей нет по устройству корпуса, и человеку,
            // решившему три задачи, «решите первую» было бы враньём.
            console.dim(solved > 0 ? Msg.t("profile.empty.code") : Msg.t("profile.empty"));
            return;
        }

        double profileMax = profile.get("axisMax").asDouble(FALLBACK_AXIS_MAX);
        for (String axis : names) {
            Json.Node a = axes.get(axis);
            double score = a.get("score").asDouble(0);
            double max = a.get("maxScore").asDouble(profileMax);
            double fill = a.get("fill").isNumber() ? a.get("fill").asDouble(0) : (max > 0 ? score / max : 0);
            console.print(Msg.t("profile.axis", axis, bar(fill), score,
                    a.get("ceiling").asLong(0), percent(a.get("foundation").asDouble(0))));
        }
        console.print("");
    }

    /** Полоска. Глазом виден перекос профиля, а он и есть главное. */
    static String bar(double fill) {
        double clamped = Math.max(0, Math.min(1, fill));
        int filled = (int) Math.round(clamped * BAR_WIDTH);
        return "█".repeat(filled) + "·".repeat(BAR_WIDTH - filled);
    }

    /** Доля 0..1 — процентами; меньше процента, но не ноль — «&lt;1». */
    static String percent(double share) {
        double value = share * 100;
        if (value > 0 && value < 1) {
            return "<1";
        }
        return String.valueOf(Math.round(value));
    }
}
