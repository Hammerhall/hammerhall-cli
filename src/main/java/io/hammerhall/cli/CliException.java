package io.hammerhall.cli;

/**
 * Ожидаемая ошибка: нет токена, файл не найден, сервер отказал.
 *
 * <p>Отличается от прочих исключений тем, что её текст показывается
 * пользователю целиком и без стектрейса. Значит формулировка — часть
 * интерфейса: «Файл не найден: X» полезно, «NoSuchFileException» нет.
 */
public class CliException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CliException(String message) {
        super(message);
    }

    public CliException(String message, Throwable cause) {
        super(message, cause);
    }
}
