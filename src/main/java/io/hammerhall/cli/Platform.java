package io.hammerhall.cli;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Адрес платформы — куда уходят ключ и, при входе, пароль.
 *
 * <h3>🔴 Почему адрес больше не берётся из файла в кузнице (0.2.0)</h3>
 *
 * До 0.2.0 адрес читался из {@code .hammerhall/config} в корне кузницы —
 * из файла внутри клонированного репозитория. Кузницу клонируют и форкают;
 * одной строки {@code api=http://…} в чужом форке или в архиве «кузница
 * с моими решениями, разбирай» хватало, чтобы {@code hammerhall login}
 * отправил почту и пароль постороннему — молча (ревью CLI №1, БЛОК-3).
 *
 * <p>Теперь адрес по умолчанию прибит: {@value #DEFAULT}. Переопределить —
 * только явно и только снаружи репозитория: переменной окружения
 * {@code HAMMERHALL_API} или флагом {@code --api}, и оба печатают громкую
 * строку «адрес платформы переопределён». Отладка своего стенда — редкое
 * действие, она переживёт одну лишнюю строку. Строка {@code api=} в файле
 * кузницы больше не действует — и клиент говорит об этом.
 *
 * <p>{@code http://} — только для своей машины ({@code localhost},
 * {@code 127.x}, {@code ::1}): по открытой сети ключ уехал бы открытым текстом.
 */
public record Platform(URI base, boolean overridden) {

    public static final String DEFAULT = "https://api.hammerhall.io";

    /** Платформа по умолчанию. */
    public static Platform standard() {
        return new Platform(URI.create(DEFAULT), false);
    }

    /**
     * Выбрать адрес.
     *
     * @param flag       значение {@code --api} или {@code null}
     * @param env        значение {@code HAMMERHALL_API} или {@code null}
     * @param legacyFile строка {@code api=} из старого файла кузницы или {@code null}
     */
    public static Platform resolve(String flag, String env, String legacyFile, Console console) {
        if (legacyFile != null && !legacyFile.isBlank() && !isDefault(legacyFile)) {
            console.warn(Msg.t("platform.legacy.ignored", legacyFile.trim()));
        }
        String override = flag != null && !flag.isBlank() ? flag
                : env != null && !env.isBlank() ? env
                : null;
        if (override == null) {
            return standard();
        }
        URI uri = validate(override.trim());
        if (isDefault(uri.toString())) {
            return standard();
        }
        console.warn(Msg.t("platform.overridden", uri));
        return new Platform(uri, true);
    }

    /** Ключ, под которым хранится ключ доступа этой платформы: схема, хост и порт. */
    public String key() {
        return base.getScheme() + "://" + base.getRawAuthority();
    }

    @Override
    public String toString() {
        return base.toString();
    }

    static boolean isDefault(String url) {
        return trimSlash(url.trim()).equalsIgnoreCase(DEFAULT);
    }

    static URI validate(String raw) {
        URI uri;
        try {
            uri = new URI(trimSlash(raw));
        } catch (URISyntaxException e) {
            throw new CliException(Msg.t("platform.bad", raw));
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (uri.getHost() == null || !(scheme.equals("https") || scheme.equals("http"))) {
            throw new CliException(Msg.t("platform.bad", raw));
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
            throw new CliException(Msg.t("platform.bad", raw));
        }
        if (scheme.equals("http") && !isLoopback(uri.getHost())) {
            throw new CliException(Msg.t("platform.http", raw));
        }
        return uri;
    }

    /**
     * Своя машина. Имя проверяем по написанию, а не через DNS: {@code localhost}
     * мог бы и разрешиться куда угодно в чужой сети, но так пишут только своё.
     */
    static boolean isLoopback(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.equals("localhost") || h.startsWith("[")) {
            h = h.replace("[", "").replace("]", "");
        }
        if (h.equals("localhost")) {
            return true;
        }
        if (!h.matches("[0-9.]+") && !h.contains(":")) {
            return false;   // имя хоста — не литерал адреса, не своё
        }
        try {
            return InetAddress.getByName(h).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
