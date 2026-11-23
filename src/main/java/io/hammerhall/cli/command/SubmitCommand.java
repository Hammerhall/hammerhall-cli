package io.hammerhall.cli.command;

import io.hammerhall.cli.ApiClient;
import io.hammerhall.cli.Args;
import io.hammerhall.cli.CliException;
import io.hammerhall.cli.Console;
import io.hammerhall.cli.Context;
import io.hammerhall.cli.ForgeRoot;
import io.hammerhall.cli.Ids;
import io.hammerhall.cli.Json;
import io.hammerhall.cli.Msg;
import io.hammerhall.cli.RetryAfter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Отправить решение на проверку.
 *
 * <p>На сервер уезжает ТЕКСТ ОДНОГО ФАЙЛА — несколько килобайт. Файл
 * ищется по имени внутри {@code src/main/java}: задачи лежат глубоко
 * в дереве пакетов, и помнить путь никто не обязан. Номер задачи — имя
 * файла: по стандарту кузницы имя класса и есть номер.
 *
 * <h3>Что изменилось в 0.2.0 (ревью CLI №1)</h3>
 *
 * <ul>
 *   <li><b>Протокол с очередью</b> (БЛОК-1): {@code POST /submit} отвечает
 *       либо вердиктом (200, как сейчас), либо «принято» (202 с номером) —
 *       и тогда клиент опрашивает {@code GET /submit/{номер}}, показывая
 *       «в очереди…». Сервер сегодня всегда отвечает 200, и ничего не
 *       меняется; но когда приедет очередь, клиенты, умеющие 202, уже будут
 *       у людей. Иначе все разъехавшиеся клиенты сломались бы разом.</li>
 *   <li><b>Все восемь вердиктов</b> — человеческими словами, включая
 *       {@code CRASHED} (в него платформа сводит всё неизвестное) и
 *       {@code RESOURCE_LIMIT}; ответ без статуса — не «Вердикт: null»
 *       (БЛОК-5).</li>
 *   <li><b>Размер — до чтения</b> (ВЫС-4): промахнулись мимо файла —
 *       «больше 256 КБ», а не гигабайт в памяти и {@code OutOfMemoryError}.</li>
 *   <li><b>Не читается файл — слова, а не стектрейс</b> (ВЫС-5).</li>
 *   <li><b>Только UTF-8</b> (ВЫС-6): файл в cp1251 больше не превращает
 *       кириллицу в {@code U+FFFD} молча — человеку говорят, как пересохранить;
 *       BOM срезается.</li>
 *   <li><b>Только внутри кузницы</b> (ВЫС-10): {@code submit ~/.ssh/id_rsa}
 *       до 0.2.0 отправил бы ключ на платформу. Теперь путь сверяется
 *       с корнем кузницы по настоящему пути, со ссылками.</li>
 *   <li><b>Тело — с экранированием всех полей</b> (ВЫС-9), разбор ответа —
 *       настоящим разборщиком: «0/0 тестов» от пробела после двоеточия
 *       больше не бывает (ВЫС-1).</li>
 *   <li><b>Долгая проверка — не «сбой связи»</b> (СР-8): решение не
 *       потеряно, повтор вернёт вердикт из истории.</li>
 * </ul>
 */
public final class SubmitCommand implements Command {

    /** Тот же потолок, что на сервере: смысла отправлять больше нет. */
    static final int MAX_CODE_BYTES = 256 * 1024;

    /** Шаг опроса, если сервер не сказал свой. */
    static final Duration POLL = Duration.ofSeconds(2);

    /** Сколько всего ждать вердикта в очереди. */
    static final Duration AWAIT_LIMIT = Duration.ofMinutes(10);

    /** Ожидание синхронного вердикта; тест сжимает его, чтобы не ждать две минуты. */
    static Duration syncTimeout = ApiClient.SUBMIT_TIMEOUT;

    @Override
    public String name() {
        return "submit";
    }

    @Override
    public int run(Args args, Context context) throws IOException {
        args.requireCount(1, 1, "hammerhall submit MovCode0002.java");
        ForgeRoot.Found forge = context.forge();
        Console console = context.console();

        Path file = locate(forge.root(), context.workDir(), args.rest().getFirst());
        String taskId = Ids.task(PullCommand.stripJava(file.getFileName().toString()));
        String code = read(file);

        console.dim(Msg.t("submit.sending", taskId));
        String body = Json.object("forgeId", forge.id(), "taskId", taskId, "code", code);

        Json.Node verdict;
        try (ApiClient api = context.authorized()) {
            ApiClient.Response response = api.post(body, syncTimeout, "submit");
            verdict = response.status() == 202 ? await(api, response, context) : response.body();
        } catch (CliException e) {
            if (e.kind() == CliException.Kind.TIMEOUT) {
                throw new CliException(Msg.t("submit.slow"), 0, CliException.Kind.TIMEOUT);
            }
            throw e;
        }
        return report(console, verdict);
    }

    /**
     * Решение принято в очередь — ждать вердикта, спрашивая сервер.
     * Шаг — {@code Retry-After} ответа 202, если сервер его назвал.
     * Предел считается по сумме пауз, а не по часам: так он одинаков
     * и на спящем ноутбуке, и в тесте.
     */
    private static Json.Node await(ApiClient api, ApiClient.Response accepted, Context context) {
        String id = accepted.body().get("submissionId").text();
        if (id == null) {
            throw new CliException(Msg.t("submit.queued.noid"));
        }
        Console console = context.console();
        ApiClient.Response state = accepted;
        String shown = null;
        Duration waited = Duration.ZERO;
        while (true) {
            String status = state.body().get("status").text();
            if (!Objects.equals(status, shown)) {
                console.dim("RUNNING".equals(status) ? Msg.t("submit.running") : Msg.t("submit.queued"));
                shown = status;
            }
            Duration pause = RetryAfter.parse(state.headers().firstValue("Retry-After").orElse(null), context.clock());
            if (pause == null || pause.isZero() || pause.compareTo(Duration.ofSeconds(30)) > 0) {
                pause = POLL;
            }
            waited = waited.plus(pause);
            if (waited.compareTo(AWAIT_LIMIT) > 0) {
                throw new CliException(Msg.t("submit.slow"), 0, CliException.Kind.TIMEOUT);
            }
            try {
                context.sleeper().sleep(pause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CliException(Msg.t("api.interrupted"), e);
            }
            state = api.get("submit", id);
            if (state.status() != 202) {
                return state.body();
            }
        }
    }

    /** Печатает вердикт. Код выхода: 0 — зачтено, 1 — нет. */
    static int report(Console console, Json.Node verdict) {
        String status = verdict.get("status").text();
        Json.Node tests = verdict.get("tests");
        long total = tests.get("total").asLong(0);
        long passed = tests.get("passed").asLong(0);
        long duration = verdict.get("durationMs").asLong(0);

        switch (status == null ? "" : status) {
            case "PASSED" -> console.success(Msg.t("submit.passed", passed, total, duration));
            case "FAILED" -> {
                console.error(Msg.t("submit.failed", passed, total));
                printFailures(console, verdict);
            }
            case "STYLE_VIOLATION" -> {
                console.error(Msg.t("submit.style"));
                printLog(console, verdict);
            }
            case "COMPILE_ERROR" -> {
                console.error(Msg.t("submit.compile"));
                printLog(console, verdict);
            }
            case "TIMEOUT" -> console.error(Msg.t("submit.timeout"));
            case "RESOURCE_LIMIT" -> {
                console.error(Msg.t("submit.resource"));
                printLog(console, verdict);
            }
            case "CRASHED" -> {
                console.error(Msg.t("submit.crashed"));
                printLog(console, verdict);
            }
            case "INVALID_INPUT" -> {
                console.error(Msg.t("submit.invalid"));
                printLog(console, verdict);
            }
            case "" -> {
                console.error(Msg.t("submit.nostatus"));
                printLog(console, verdict);
            }
            default -> {
                console.error(Msg.t("submit.unknown", status));
                printLog(console, verdict);
            }
        }

        if (verdict.get("fromHistory").asBool(false)) {
            // Иначе мгновенный ответ выглядит как сбой: человек ждал проверки,
            // а получил результат сразу.
            console.dim(Msg.t("submit.history"));
        }
        return "PASSED".equals(status) ? 0 : 1;
    }

    private static void printFailures(Console console, Json.Node verdict) {
        List<String> failures = verdict.get("failures").strings();
        if (failures.isEmpty()) {
            return;
        }
        console.print("");
        console.print(Msg.t("submit.failures"));
        // Только ИМЕНА тестов — сервер и не присылает больше: сообщения
        // ассертов содержали бы ожидаемые значения, а это подсказка к ответу.
        failures.forEach(name -> console.print("  · " + name));
    }

    private static void printLog(Console console, Json.Node verdict) {
        String log = verdict.get("log").text();
        if (log != null) {
            console.print("");
            log.lines().forEach(line -> console.print("  " + line));
        }
    }

    /**
     * Найти файл: путь как есть (от каталога запуска), путь от корня кузницы,
     * поиск по имени в {@code src/main/java}. Найденное — только внутри кузницы.
     */
    static Path locate(Path root, Path workDir, String argument) throws IOException {
        Path found = find(root, workDir, argument);
        Path real;
        Path realRoot;
        try {
            real = found.toRealPath();
            realRoot = root.toRealPath();
        } catch (IOException e) {
            throw new CliException(Msg.t("submit.unreadable", found, e.getMessage()), e);
        }
        if (!real.startsWith(realRoot)) {
            throw new CliException(Msg.t("submit.outside", argument));
        }
        return real;
    }

    private static Path find(Path root, Path workDir, String argument) throws IOException {
        Path direct = workDir.resolve(argument);
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        Path fromRoot = root.resolve(argument);
        if (Files.isRegularFile(fromRoot)) {
            return fromRoot;
        }

        String name = Path.of(argument).getFileName().toString();
        if (!name.endsWith(".java")) {
            name = name + ".java";
        }
        Path sources = root.resolve("src/main/java");
        if (!Files.isDirectory(sources)) {
            throw new CliException(Msg.t("submit.nosources"));
        }

        List<Path> found;
        String target = name;
        try (Stream<Path> walk = Files.walk(sources)) {
            found = walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(target))
                    .toList();
        }
        if (found.isEmpty()) {
            throw new CliException(Msg.t("submit.notfound", name));
        }
        if (found.size() > 1) {
            // Не выбираем за человека: два файла с одним именем — разные задачи.
            StringBuilder list = new StringBuilder();
            found.forEach(path -> list.append("\n    ").append(ForgeRoot.shown(root, path)));
            throw new CliException(Msg.t("submit.ambiguous", list));
        }
        return found.getFirst();
    }

    /** Прочитать решение: размер — до чтения, кодировка — строго UTF-8, BOM — долой. */
    static String read(Path file) {
        byte[] bytes;
        try {
            if (Files.size(file) > MAX_CODE_BYTES) {
                throw new CliException(Msg.t("submit.toobig", MAX_CODE_BYTES / 1024));
            }
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new CliException(Msg.t("submit.unreadable", file, e.getMessage()), e);
        }
        String code;
        try {
            code = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new CliException(Msg.t("submit.encoding", file.getFileName()));
        }
        // BOM ставят редакторы Windows; javac ответил бы «illegal character».
        return code.startsWith("\uFEFF") ? code.substring(1) : code;
    }
}
