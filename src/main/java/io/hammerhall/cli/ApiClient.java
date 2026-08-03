package io.hammerhall.cli;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Разговор с платформой.
 *
 * <h3>Почему java.net.http, а не библиотека</h3>
 *
 * Встроен в JDK с 11-й версии. Четыре запроса не стоят зависимости,
 * которую надо версионировать, тянуть в сборку и обновлять при уязвимостях.
 * Клиент должен оставаться одним файлом jar без зависимостей — тогда
 * обёртка качает его за секунду.
 *
 * <h3>Таймаут</h3>
 *
 * Две минуты на ответ. Кажется много, но проверка решения — это ЗАПУСК
 * контейнера: компиляция, байткод-скан, прогон закрытых тестов. Боевой
 * замер — 1007 мс, однако между запросом и контейнером стоит ОЧЕРЕДЬ:
 * платформа держит пул раннеров (бюджет памяти хоста), и под нагрузкой
 * решение ждёт своей очереди.
 *
 * <p>⚠️ Число связано с сервером: предел ЗДЕСЬ обязан быть больше
 * {@code submission.await-timeout-seconds} платформы (90 с). Иначе клиент
 * сдаётся раньше, чем платформа заканчивает проверку: человек видит «сбой
 * связи», хотя решение считается нормально, и отправляет повторно — а там
 * уже идемпотентность. Меняете предел на сервере — правьте и здесь.
 */
public class ApiClient {

    /**
     * Предел ожидания ответа. ОБЯЗАН быть больше серверного
     * {@code submission.await-timeout-seconds} (90 с) — см. javadoc класса.
     */
    private static final Duration RESPONSE_TIMEOUT = Duration.ofMinutes(2);

    private final String baseUrl;

    /** Ключ доступа или {@code null} — для ручек, которые зовут ДО его получения. */
    private final String token;

    private final HttpClient http;

    public ApiClient(String baseUrl, String token) {
        this.baseUrl = baseUrl;
        this.token = token;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Клиент без ключа — для {@code POST /auth/token}.
     *
     * <h3>Почему отдельный конструктор, а не {@code null} вторым аргументом</h3>
     *
     * Анонимный вызов у клиента ровно один, и он должен быть виден в коде
     * как решение, а не как случайно не переданный параметр. {@code null}
     * в аргументе читается одинаково и когда так задумано, и когда забыли.
     *
     * <p>⚠️ Заголовок {@code Authorization} при этом НЕ ставится вовсе —
     * ни пустой, ни {@code Bearer null}. Фильтр платформы к негодному
     * значению снисходителен (не аутентифицирует и пропускает дальше),
     * так что вреда не было бы и от мусора, но полагаться на чужую
     * снисходительность — значит зависеть от того, чего мы не контролируем.
     *
     * <p>🔑 CSRF здесь не нужен: {@code /auth/token} освобождён от него
     * в {@code SecurityConfig} — ручка авторизуется телом и куки не ставит.
     */
    public ApiClient(String baseUrl) {
        this(baseUrl, null);
    }

    public String get(String path) {
        return send(HttpRequest.newBuilder(uri(path)).GET());
    }

    public String post(String path, String jsonBody) {
        return send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8)));
    }

    private String send(HttpRequest.Builder builder) {
        builder.header("Accept", "application/json").timeout(RESPONSE_TIMEOUT);

        // Ключа нет — заголовка нет. Единственный такой вызов: обмен почты
        // и пароля на ключ, то есть шаг, на котором ключа ещё неоткуда взяться.
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }

        HttpRequest request = builder.build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (ConnectException e) {
            throw new CliException("Платформа недоступна: " + baseUrl, e);
        } catch (IOException e) {
            throw new CliException("Сбой связи: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CliException("Прервано", e);
        }

        return handle(response);
    }

    private String handle(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return response.body();
        }

        // Коды переводим в человеческие фразы. Голое «HTTP 404» ничего
        // не объясняет тому, кто просто решает задачу.
        //
        // 🔑 Число едет вместе с фразой: у ручки выдачи ключа тот же 401
        // означает другое, и LoginCommand переформулирует его сам. Здесь —
        // умолчание, верное для остальных команд.
        throw new CliException(switch (status) {
            // ⚠️ Здесь стояло «перевыпустите в личном кабинете» — кнопки там
            // нет до сих пор, и совет отправлял человека искать несуществующее.
            // Ключ теперь берёт сам клиент, поэтому единственное верное
            // указание — повторить вход.
            case 401 -> """
                    Ключ не принят. Возможно, он отозван сменой пароля или устарел.
                    Выполните: hammerhall login""";
            case 403 -> "Доступ закрыт: " + message(response.body());
            case 404 -> notFound(response);
            case 413 -> "Файл слишком большой для проверки";
            case 429 -> tooManyRequests(response);
            case 503 -> temporarilyBusy(response);
            default -> "Платформа ответила " + status + ": " + message(response.body());
        }, status);
    }

    /**
     * Не найдено — и надо объяснить, что именно.
     * <p>
     * Платформа на 404 <b>намеренно молчит</b>: подробность подтверждала бы
     * существование задачи тому, кому её видеть не положено, а для перебора
     * идентификаторов это подсказка. Логика правильная, но человек в ответ
     * получал голое «Не найдено: (без объяснения)» и не мог понять, что не
     * так: нет задачи, нет доступа или он вообще не в той кузнице.
     * <p>
     * Показываем сам запрос. Секрета в нём нет — человек только что сам его
     * и составил, — зато сразу видно, куда клиент ходил. Ровно так нашлась
     * первая же ошибка сквозного прогона: в {@code .hammerhall/forge} лежал
     * {@code project-stream-forge}, а кузница называлась {@code stream-forge}.
     */
    private String notFound(HttpResponse<String> response) {
        String explained = message(response.body());
        if ("(без объяснения)".equals(explained)) {
            return "Не найдено: " + response.uri().getPath()
                    + "\nПроверьте, та ли кузница в .hammerhall/forge и есть ли доступ к задаче.";
        }
        return "Не найдено: " + explained;
    }

    /**
     * Отказ по частоте. Сервер присылает {@code Retry-After} — используем его,
     * чтобы сказать человеку, сколько ждать, вместо расплывчатого «слишком часто».
     */
    private String tooManyRequests(HttpResponse<String> response) {
        String retryAfter = retryAfter(response);
        if (retryAfter != null) {
            return "Слишком часто. Повторите через " + retryAfter + " с.";
        }
        return "Слишком часто. Подождите немного.";
    }

    /**
     * Платформа занята: пул проверок переполнен.
     * <p>
     * Это <b>не поломка</b>. Решение не потеряно и не проверено — его надо
     * просто отправить снова. Сервер сам говорит, через сколько, и своё число
     * мы не выдумываем: поменяется политика пула — фраза поедет за ней.
     */
    private String temporarilyBusy(HttpResponse<String> response) {
        String retryAfter = retryAfter(response);
        if (retryAfter != null) {
            return "Платформа занята проверками. Повторите через " + retryAfter + " с.";
        }
        return "Платформа временно недоступна. Повторите чуть позже.";
    }

    /**
     * Через сколько повторять, если сервер сказал. Общий разбор для 429 и 503:
     * оба ответа несут {@code Retry-After} и оба означают «сейчас нельзя, позже
     * можно» — разница только в причине отказа.
     */
    private String retryAfter(HttpResponse<String> response) {
        return response.headers().firstValue("Retry-After").orElse(null);
    }

    /**
     * Достаёт поле message из ответа об ошибке.
     * <p>
     * Разбор вручную, без библиотеки JSON: нам нужно ОДНО поле из ответа,
     * который мы сами и формируем на сервере. Тянуть ради этого Jackson —
     * плюс несколько мегабайт к jar, который качается при первом запуске.
     */
    private String message(String body) {
        if (body == null || body.isBlank()) {
            return "(без объяснения)";
        }
        String extracted = Json.string(body, "message");
        return extracted != null ? extracted : body;
    }

    private URI uri(String path) {
        return URI.create(baseUrl + path);
    }
}
