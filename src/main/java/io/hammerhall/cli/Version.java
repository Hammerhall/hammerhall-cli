package io.hammerhall.cli;

/**
 * Версия клиента — откуда она берётся и как сравнивается.
 *
 * <h3>🔴 Зачем (0.2.0)</h3>
 *
 * До 0.2.0 клиент своей версии не знал: в манифесте jar её не было,
 * команды {@code version} не было, в запросах не было {@code User-Agent}.
 * Первый вопрос поддержки — «какая у вас версия?» — задать было нельзя,
 * а сервер не мог отличить 0.1.1 от 0.1.4, когда контракт поедет (а он уже
 * ехал: {@code tasksSolved} → {@code solvedTasks}). Ревью CLI №1, БЛОК-2.
 *
 * <p>Теперь версия — {@code Implementation-Version} из манифеста (её пишет
 * {@code maven-jar-plugin}), она уходит в каждом запросе заголовком
 * {@code User-Agent: hammerhall-cli/0.2.0 (java 25.0.2; Linux 6.8.0)},
 * а сервер может прислать {@code X-Hammerhall-Min-Client} — и клиент
 * старше скажет человеку «обновите кузницу», а не сломается непонятно.
 *
 * <p>⚠️ Из IDE и из тестов манифеста нет — тогда версия {@code dev}, и
 * сравнение с минимальной не делается: разработческая сборка заведомо
 * свежая.
 */
public final class Version {

    /** Версия, когда манифеста нет: IDE, тесты. */
    public static final String DEV = "dev";

    private Version() {
    }

    /** Версия этого клиента. */
    public static String current() {
        String fromManifest = Version.class.getPackage().getImplementationVersion();
        return fromManifest == null || fromManifest.isBlank() ? DEV : fromManifest;
    }

    /** Заголовок {@code User-Agent}: по нему сервер видит, какие клиенты у людей. */
    public static String userAgent(String version) {
        return "hammerhall-cli/%s (java %s; %s %s)".formatted(
                version,
                System.getProperty("java.version"),
                System.getProperty("os.name"),
                System.getProperty("os.version"));
    }

    /**
     * Старше ли {@code version}, чем {@code minimum}.
     *
     * <p>Сравнение по числам через точку: {@code 0.10.0} новее {@code 0.9.9}
     * (строкой было бы наоборот). Хвост вроде {@code -SNAPSHOT} отбрасывается.
     * Непонятная версия — «не старше»: ругаться на то, чего не понимаешь,
     * хуже, чем промолчать.
     */
    public static boolean isOlder(String version, String minimum) {
        if (version == null || minimum == null || DEV.equals(version)) {
            return false;
        }
        int[] have = parse(version);
        int[] need = parse(minimum);
        if (have == null || need == null) {
            return false;
        }
        for (int i = 0; i < Math.max(have.length, need.length); i++) {
            int a = i < have.length ? have[i] : 0;
            int b = i < need.length ? need[i] : 0;
            if (a != b) {
                return a < b;
            }
        }
        return false;
    }

    private static int[] parse(String version) {
        String core = version.trim();
        int dash = core.indexOf('-');
        if (dash >= 0) {
            core = core.substring(0, dash);
        }
        String[] parts = core.split("\\.");
        int[] numbers = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                numbers[i] = Integer.parseInt(parts[i]);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return numbers;
    }
}
