package io.hammerhall.cli;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Запись файлов — целиком или никак, а секретов — сразу с нужными правами.
 *
 * <h3>🔴 Права при создании, а не после (0.2.0)</h3>
 *
 * До 0.2.0 ключ писался {@code Files.writeString}, и только потом файлу
 * выставлялись 600. Между двумя строками файл с ключом лежал на диске
 * читаемым для всех, а каталог оставался 755 навсегда; javadoc при этом
 * обещал «права 600 при создании» (ревью CLI №1, БЛОК-4). На своём
 * ноутбуке это микроскопично, в учебном классе и на общем сервере — нет.
 *
 * <p>Теперь секрет пишется во временный файл, СОЗДАННЫЙ с правами 600,
 * в каталоге, созданном с правами 700, и атомарно переносится на место.
 * Если каталог или файл уже были — права выставляются вторым проходом.
 * На Windows POSIX-прав нет — там своя модель доступа (профиль
 * пользователя закрыт от других), это не ошибка.
 *
 * <h3>Целиком или никак</h3>
 *
 * Прерывание посреди записи (Ctrl-C, разряженная батарея) оставляло
 * обрезанный файл — решение ученика или конфиг. Временный файл рядом
 * и {@code ATOMIC_MOVE} — стандартное лечение (ревью CLI №1, СР-6).
 */
public final class SafeFiles {

    private static final Set<PosixFilePermission> OWNER_FILE =
            PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> OWNER_DIR =
            PosixFilePermissions.fromString("rwx------");

    private SafeFiles() {
    }

    /** Каталог только для владельца (700); есть — права выставляются вторым проходом. */
    public static void ownerOnlyDirectory(Path dir) throws IOException {
        if (posix(dir)) {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(OWNER_DIR));
            Files.setPosixFilePermissions(dir, OWNER_DIR);
        } else {
            Files.createDirectories(dir);
        }
    }

    /** Записать секрет: временный файл с правами 600 → атомарный перенос → 600 ещё раз. */
    public static void writeSecret(Path file, String text) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        ownerOnlyDirectory(dir);
        Path tmp = posix(dir)
                ? Files.createTempFile(dir, ".tmp-", ".part", PosixFilePermissions.asFileAttribute(OWNER_FILE))
                : Files.createTempFile(dir, ".tmp-", ".part");
        try {
            writeBytes(tmp, text);
            move(tmp, file);
            if (posix(dir)) {
                Files.setPosixFilePermissions(file, OWNER_FILE);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Записать обычный файл целиком или никак. Каталог создаётся, если его нет. */
    public static void write(Path file, String text) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, ".tmp-", ".part");
        try {
            writeBytes(tmp, text);
            move(tmp, file);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void writeBytes(Path file, String text) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(file,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer bytes = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Поддерживает ли файловая система POSIX-права. */
    static boolean posix(Path any) {
        return any.getFileSystem().supportedFileAttributeViews().contains("posix");
    }
}
