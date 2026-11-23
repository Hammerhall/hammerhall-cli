package io.hammerhall.cli;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Настоящий HTTP-сервер вместо платформы — {@code com.sun.net.httpserver}
 * из JDK, без единой зависимости и без мока {@code HttpClient}.
 *
 * <p>Мок проверял бы наши представления о клиенте HTTP, а не поведение:
 * заголовки, перенаправления, тела ошибок, {@code Retry-After}. Здесь
 * всё настоящее, а маршрут отвечает заданной очередью ответов — последний
 * повторяется (так описываются «сначала 503, потом 200» и «всегда 202»).
 */
public final class FakePlatform implements AutoCloseable {

    /** Что пришло на сервер. */
    public record Seen(String method, String path, Map<String, List<String>> headers, String body) {

        public String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
                    return e.getValue().getFirst();
                }
            }
            return null;
        }
    }

    /** Что ответить. */
    public record Reply(int status, String body, Map<String, String> headers) {
    }

    private final HttpServer server;
    private final List<Seen> seen = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Deque<Reply>> routes = new ConcurrentHashMap<>();

    public FakePlatform() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = exchange.getRequestURI().getRawPath();
            seen.add(new Seen(exchange.getRequestMethod(), path,
                    new LinkedHashMap<>(exchange.getRequestHeaders()), body));

            Reply reply = next(exchange.getRequestMethod() + " " + path);
            reply.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            byte[] payload = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status(), payload.length == 0 ? -1 : payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
    }

    private Reply next(String route) {
        Deque<Reply> queue = routes.get(route);
        if (queue == null || queue.isEmpty()) {
            return new Reply(404, "", Map.of());
        }
        synchronized (queue) {
            return queue.size() > 1 ? queue.poll() : queue.peek();
        }
    }

    /** На {@code METHOD путь} отвечать по очереди; последний ответ повторяется. */
    public FakePlatform on(String method, String path, Reply... replies) {
        routes.put(method + " " + path, new ArrayDeque<>(List.of(replies)));
        return this;
    }

    /** Ответ JSON; дальше — пары «заголовок, значение». */
    public static Reply json(int status, String body, String... headerPairs) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        for (int i = 0; i + 1 < headerPairs.length; i += 2) {
            headers.put(headerPairs[i], headerPairs[i + 1]);
        }
        return new Reply(status, body, headers);
    }

    /** Ответ не-JSON: например, HTML-страница прокси. */
    public static Reply raw(int status, String contentType, String body, String... headerPairs) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (contentType != null) {
            headers.put("Content-Type", contentType);
        }
        for (int i = 0; i + 1 < headerPairs.length; i += 2) {
            headers.put(headerPairs[i], headerPairs[i + 1]);
        }
        return new Reply(status, body, headers);
    }

    public String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    public List<Seen> seen() {
        synchronized (seen) {
            return List.copyOf(seen);
        }
    }

    /** Последний запрос на путь. */
    public Seen last(String method, String path) {
        List<Seen> all = seen();
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).method().equals(method) && all.get(i).path().equals(path)) {
                return all.get(i);
            }
        }
        throw new AssertionError("не было запроса " + method + " " + path + "; были: " + all);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
