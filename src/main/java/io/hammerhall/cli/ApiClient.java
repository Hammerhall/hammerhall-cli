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

    public String get(String path) {
        return send(HttpRequest.newBuilder(uri(path)).GET());
    }

    public String post(String path, String jsonBody) {
        return send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8)));
    }

    private String send(HttpRequest.Builder builder) {
        HttpRequest request = builder
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .timeout(RESPONSE_TIMEOUT)
                .build();

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
        throw new CliException(switch (status) {
            case 401 -> """
                    Токен не принят. Возможно, он устарел или скопирован не полностью.
                    Перевыпустите в личном кабинете и выполните: hammerhall login""";
            case 403 -> "Доступ закрыт: " + message(response.body());
            case 404 -> "Не найдено: " + message(response.body());
            case 413 -> "Файл слишком большой для проверки";
            case 429 -> tooManyRequests(response);
            case 503 -> temporarilyBusy(response);
            default -> "Платформа ответила " + status + ": " + message(response.body());
        });
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
