package io.hammerhall.cli;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.StringJoiner;

/**
 * Разговор с платформой.
 *
 * <h3>Почему java.net.http, а не библиотека</h3>
 *
 * Встроен в JDK. Пять ручек не стоят зависимости, которую надо
 * версионировать и обновлять при уязвимостях: клиент — один jar.
 *
 * <h3>Что изменилось в 0.2.0 (ревью CLI №1)</h3>
 *
 * <ul>
 *   <li><b>Перенаправления не выполняются</b> ({@code Redirect.NEVER}).
 *       API не должно редиректить вовсе; редирект означает, что между
 *       клиентом и платформой кто-то встал, и ключ в заголовке не должен
 *       уехать за ним. 3xx — понятная ошибка «проверьте адрес и прокси»
 *       (БЛОК-3).</li>
 *   <li><b>Каждый запрос несёт версию</b> — {@code User-Agent}; а ответ
 *       может нести {@code X-Hammerhall-Min-Client}: клиент старше скажет
 *       «обновите кузницу», один раз за запуск (БЛОК-2). {@code 426} —
 *       сервер такую версию уже не принимает.</li>
 *   <li><b>Путь — из закодированных сегментов</b>, а не склейкой строк:
 *       пробел в имени каталога больше не даёт стектрейс из
 *       {@code URI.create} (ВЫС-9).</li>
 *   <li><b>{@code Retry-After} — и секундами, и датой</b> (ВЫС-11);
 *       короткий срок клиент выжидает сам и повторяет, до двух раз, с
 *       честной строкой «повторю через 5 с»: сервер прислал инструкцию,
 *       клиент её выполняет (СР-7). Повтор безопасен: отправка решения
 *       на сервере идемпотентна. Долгий срок (квота: «следующие завтра»)
 *       не выжидается — человеку говорится, когда.</li>
 *   <li><b>Таймаут ответа — не «сбой связи»</b>: отдельный вид ошибки,
 *       чтобы {@code submit} мог сказать «решение не потеряно» (СР-8).</li>
 *   <li><b>Тело ошибки — только если это JSON</b>, и не длиннее 300 знаков:
 *       HTML-страница 502 от прокси больше не выливается в консоль (СР-9).</li>
 *   <li><b>{@link HttpClient} закрывается</b> — {@code try-with-resources}
 *       (СР-14).</li>
 * </ul>
 */
public final class ApiClient implements AutoCloseable {

    /** Ожидание ответа по умолчанию. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Ожидание вердикта на синхронном {@code /submit}. ОБЯЗАНО быть больше
     * серверного {@code submission.await-timeout-seconds} (90 с): иначе
     * клиент сдаётся раньше, чем платформа заканчивает проверку.
     */
    public static final Duration SUBMIT_TIMEOUT = Duration.ofMinutes(2);

    /** Срок {@code Retry-After}, который клиент выжидает сам. Дольше — решает человек. */
    static final Duration AUTO_RETRY_LIMIT = Duration.ofSeconds(30);

    /** Сколько раз повторять по {@code Retry-After}. */
    static final int AUTO_RETRIES = 2;

    /** Заголовок ответа: минимальная версия клиента, которую ждёт платформа. */
    public static final String MIN_CLIENT_HEADER = "X-Hammerhall-Min-Client";

    /** Тело ошибки длиннее — обрезается. */
    static final int ERROR_BODY_LIMIT = 300;

    /** Ожидание, которое можно подменить в тесте. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /** Ответ платформы с кодом 2xx: код, разобранное тело, заголовки. */
    public record Response(int status, Json.Node body, HttpHeaders headers) {
    }

    private final Platform platform;

    /** Ключ доступа или {@code null} — для обмена почты и пароля на ключ. */
    private final String token;

    private final Context context;
    private final HttpClient http;

    ApiClient(Platform platform, String token, Context context) {
        this.platform = platform;
        this.token = token;
        this.context = context;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public Response get(String... segments) {
        return send("GET", null, DEFAULT_TIMEOUT, segments);
    }

    public Response post(String jsonBody, Duration timeout, String... segments) {
        return send("POST", jsonBody, timeout, segments);
    }

    /** Запрос с повтором по короткому {@code Retry-After}; не 2xx — {@link CliException}. */
    public Response send(String method, String jsonBody, Duration timeout, String... segments) {
        URI uri = uri(segments);
        for (int attempt = 0; ; attempt++) {
            HttpResponse<String> response = exchange(method, jsonBody, timeout, uri);
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return new Response(status, successBody(response), response.headers());
            }
            if ((status == 429 || status == 503) && attempt < AUTO_RETRIES) {
                Duration wait = RetryAfter.parse(
                        response.headers().firstValue("Retry-After").orElse(null), context.clock());
                if (wait != null && wait.compareTo(AUTO_RETRY_LIMIT) <= 0) {
                    Duration pause = wait.isZero() ? Duration.ofSeconds(1) : wait;
                    context.console().dim(Msg.t("api.retry.auto", RetryAfter.human(pause)));
                    pauseFor(pause);
                    continue;
                }
            }
            throw refusal(response);
        }
    }

    private HttpResponse<String> exchange(String method, String jsonBody, Duration timeout, URI uri) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("User-Agent", Version.userAgent(context.version()));
        if (jsonBody != null) {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        // Ключа нет — заголовка нет: ни пустого, ни «Bearer null».
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }

        if (context.verbose()) {
            context.console().trace("→ " + method + " " + uri);
        }
        long started = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpConnectTimeoutException e) {
            throw new CliException(Msg.t("api.noanswer", platform), CliException.Kind.UNAVAILABLE, e);
        } catch (HttpTimeoutException e) {
            throw new CliException(Msg.t("api.timeout", timeout.toSeconds()), CliException.Kind.TIMEOUT, e);
        } catch (ConnectException e) {
            throw new CliException(Msg.t("api.down", platform), CliException.Kind.UNAVAILABLE, e);
        } catch (IOException e) {
            throw new CliException(Msg.t("api.broken", String.valueOf(e.getMessage())),
                    CliException.Kind.UNAVAILABLE, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CliException(Msg.t("api.interrupted"), e);
        }
        if (context.verbose()) {
            context.console().trace("← " + response.statusCode() + " ("
                    + (System.nanoTime() - started) / 1_000_000 + " мс)");
        }
        checkMinimum(response.headers());
        return response;
    }

    private void pauseFor(Duration pause) {
        try {
            context.sleeper().sleep(pause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CliException(Msg.t("api.interrupted"), e);
        }
    }

    /** Платформа сказала, что ждёт клиента новее, — сказать человеку один раз за запуск. */
    private void checkMinimum(HttpHeaders headers) {
        headers.firstValue(MIN_CLIENT_HEADER).ifPresent(minimum -> {
            if (Version.isOlder(context.version(), minimum)) {
                context.console().warnOnce(MIN_CLIENT_HEADER,
                        Msg.t("api.outdated.soft", context.version(), minimum.trim()));
            }
        });
    }

    /** Тело успешного ответа: пустое — {@link Json.Node#MISSING}; не JSON — понятная ошибка. */
    private Json.Node successBody(HttpResponse<String> response) {
        String body = response.body();
        if (body == null || body.isBlank()) {
            return Json.Node.MISSING;
        }
        try {
            return Json.parse(body);
        } catch (IllegalArgumentException e) {
            throw new CliException(Msg.t("api.notjson", response.statusCode()), response.statusCode());
        }
    }

    // ---------- отказы ----------

    /**
     * Отказ платформы — в слова. Голое «HTTP 404» ничего не объясняет тому,
     * кто просто решает задачу. Число едет вместе с фразой: у ручки входа
     * тот же 401 означает другое, и {@code login} переформулирует его сам.
     */
    private CliException refusal(HttpResponse<String> response) {
        int status = response.statusCode();
        Json.Node body = errorBody(response);
        String message = body.get("message").text();
        String explained = message != null ? message : excerpt(body);
        Duration wait = RetryAfter.parse(response.headers().firstValue("Retry-After").orElse(null), context.clock());

        if (status >= 300 && status < 400) {
            return new CliException(Msg.t("api.redirect", status,
                    response.headers().firstValue("Location").orElse("?")), status);
        }
        return switch (status) {
            case 400 -> new CliException(Msg.t("api.bad", orNone(explained)), status);
            case 401 -> new CliException(Msg.t("api.unauthorized"), status);
            case 403 -> new CliException(Msg.t("api.forbidden", orNone(explained)), status);
            case 404 -> new CliException(explained != null
                    ? Msg.t("api.notfound", explained)
                    : Msg.t("api.notfound.path", response.uri().getPath()), status);
            case 413 -> new CliException(Msg.t("api.toolarge"), status);
            case 426 -> new CliException(Msg.t("api.outdated", context.version(), orNone(explained)), status);
            case 429 -> new CliException(message != null ? message
                    : wait != null ? Msg.t("api.toomany.in", RetryAfter.human(wait))
                    : Msg.t("api.toomany"), status);
            case 503 -> new CliException(wait != null
                    ? Msg.t("api.busy.in", RetryAfter.human(wait))
                    : Msg.t("api.busy"), status, CliException.Kind.UNAVAILABLE);
            case 502, 504 -> new CliException(Msg.t("api.gateway", status), status, CliException.Kind.UNAVAILABLE);
            default -> new CliException(Msg.t("api.status", status, orNone(explained)), status,
                    status >= 500 ? CliException.Kind.UNAVAILABLE : CliException.Kind.ERROR);
        };
    }

    /** Тело ошибки — только если это JSON; HTML-страница прокси человеку не нужна. */
    private static Json.Node errorBody(HttpResponse<String> response) {
        String type = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        String body = response.body();
        if (!type.contains("json") || body == null || body.isBlank()) {
            return Json.Node.MISSING;
        }
        try {
            return Json.parse(body);
        } catch (IllegalArgumentException e) {
            return Json.Node.MISSING;
        }
    }

    private static String excerpt(Json.Node body) {
        String compact = body.compact();
        if (compact.isEmpty() || compact.equals("{}")) {
            return null;
        }
        return compact.length() > ERROR_BODY_LIMIT ? compact.substring(0, ERROR_BODY_LIMIT) + "…" : compact;
    }

    private static String orNone(String text) {
        return text != null ? text : Msg.t("api.noexplanation");
    }

    private URI uri(String... segments) {
        String base = platform.base().toString();
        StringJoiner path = new StringJoiner("/", base.endsWith("/") ? "" : "/", "");
        for (String segment : segments) {
            path.add(Ids.segment(segment));
        }
        return URI.create(base + path);
    }

    @Override
    public void close() {
        http.close();
    }
}
