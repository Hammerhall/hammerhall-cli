package io.hammerhall.cli;

/**
 * Ожидаемая ошибка: нет ключа, файл не найден, сервер отказал.
 *
 * <p>Отличается от прочих исключений тем, что её текст показывается
 * пользователю целиком и без стектрейса. Значит формулировка — часть
 * интерфейса: «Файл не найден: X» полезно, «NoSuchFileException» нет.
 *
 * <h3>🔑 Зачем здесь код ответа</h3>
 *
 * Один и тот же код у разных ручек означает разное, и перевести его
 * в слова может только тот, кто знает, куда ходил. {@code 401} на
 * {@code /profile} — «ключ не принят, войдите заново». {@code 401} на
 * {@code /auth/token} — «не подошли почта или пароль»: ключа там ещё
 * нет и перевыпускать нечего. Поэтому {@link ApiClient} переводит коды
 * сам — это верное умолчание, — но оставляет число в исключении:
 * вызывающий со своим смыслом ловит и говорит своими словами.
 *
 * <p>⚠️ {@code status == 0} — «не от HTTP»: нет ключа, файл не найден,
 * корень кузницы не найден. Кода ответа с таким номером не существует.
 *
 * <h3>🔑 Зачем здесь вид ошибки (0.2.0)</h3>
 *
 * Код возврата — тоже интерфейс: его читает скрипт, а не человек.
 * До 0.2.0 у «решение не прошло» и у «нет связи» был один и тот же
 * код 1 — скрипт не мог отличить одно от другого (ревью CLI №1, СР-14).
 * Теперь вид ошибки сам говорит, какой код вернуть:
 * {@link Kind#exitCode()}.
 */
public class CliException extends RuntimeException {

    /**
     * Что случилось — с точки зрения того, кто запускал клиент.
     *
     * <p>Коды возврата клиента:
     * <ul>
     *   <li>0 — всё прошло (решение зачтено);</li>
     *   <li>1 — решение не зачтено (это не ошибка клиента — вердикт);</li>
     *   <li>2 — ошибка: неверный вызов, файл, отказ платформы;</li>
     *   <li>3 — платформа недоступна: нет связи, не ответила вовремя, сбой у неё.</li>
     * </ul>
     */
    public enum Kind {
        /** Неверный вызов, файл, отказ платформы — исправляет человек. */
        ERROR(2),
        /** Платформа недоступна или ответила сбоем — исправится само, надо повторить. */
        UNAVAILABLE(3),
        /** Платформа не успела ответить — повторить; решение не потеряно. */
        TIMEOUT(3);

        private final int exitCode;

        Kind(int exitCode) {
            this.exitCode = exitCode;
        }

        public int exitCode() {
            return exitCode;
        }
    }

    /** Код ответа платформы или 0, если ошибка не от HTTP. */
    private final int status;

    private final Kind kind;

    public CliException(String message) {
        this(message, 0, Kind.ERROR);
    }

    public CliException(String message, int status) {
        this(message, status, Kind.ERROR);
    }

    public CliException(String message, int status, Kind kind) {
        super(message);
        this.status = status;
        this.kind = kind;
    }

    public CliException(String message, Throwable cause) {
        this(message, Kind.ERROR, cause);
    }

    public CliException(String message, Kind kind, Throwable cause) {
        super(message, cause);
        this.status = 0;
        this.kind = kind;
    }

    /** Код ответа платформы; 0 — ошибка возникла не из ответа. */
    public int status() {
        return status;
    }

    public Kind kind() {
        return kind;
    }
}
