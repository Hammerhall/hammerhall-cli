package io.hammerhall.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Слова клиента живут в {@code messages.properties} (ревью CLI №1, СР-14) —
 * и цена этого одна: ключ, которого нет в файле, упал бы у человека
 * {@code MissingResourceException}. Поэтому сверка — здесь, до сборки,
 * в обе стороны: нет ключа — красный; есть, но никем не зовётся — тоже,
 * чтобы мёртвые фразы не копились.
 */
@DisplayName("Msg — каждая фраза на месте, лишних нет")
class MsgTest {

    private static final Pattern CALL = Pattern.compile("Msg\\.t\\(\"([a-z0-9.]+)\"");

    @Test
    @DisplayName("ключи в коде и в файле совпадают")
    void keysMatch() throws IOException {
        Set<String> used = new HashSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = CALL.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) {
                    used.add(m.group(1));
                }
            }
        }
        Properties bundle = new Properties();
        try (InputStream in = Msg.class.getResourceAsStream("messages.properties")) {
            bundle.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        }
        assertThat(bundle.stringPropertyNames()).as("ключи файла").containsExactlyInAnyOrderElementsOf(used);
    }

    @Test
    @DisplayName("подстановка — в Locale.ROOT: точка, а не запятая (СР-3)")
    void rootLocale() {
        assertThat(Msg.t("profile.axis", "SEQ", "##", 4.5, 3L, "70")).contains("4.5").contains("70%");
    }
}
