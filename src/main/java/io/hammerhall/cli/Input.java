package io.hammerhall.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Ввод с клавиатуры: обычный и скрытый.
 *
 * <h3>🔴 Скрыт ли ввод — решает признак терминала (0.2.0)</h3>
 *
 * До 0.2.0 пароль читался через {@code System.console() != null} — ту самую
 * проверку, от которой проект отказался в двух других местах: с JDK 22
 * {@code System.console()} не {@code null} и при перенаправлении. На Java 25
 * ветка «ввод не скрывается» не срабатывала никогда, предупреждение не
 * показывалось, а из IDE или конвейера пароль печатался на экран
 * (ревью CLI №1, ВЫС-7). Теперь — {@link Console#isTerminal()}, тот же
 * признак, что выбирает кодировку и цвет: один ответ на все три вопроса.
 */
public interface Input {

    /** Строка ввода; конец ввода — {@code null}. */
    String line();

    /** Секрет: скрыто, если {@link #hidden()}, иначе обычной строкой. */
    char[] secret(String prompt);

    /** Будет ли секрет скрыт при вводе. */
    boolean hidden();

    /** Ввод с клавиатуры: скрытый — только в настоящем терминале. */
    static Input system(Console console, InputStream in) {
        return new Input() {

            /**
             * Один читатель на весь запуск: закрой мы его после первого
             * вопроса — закрылся бы {@code System.in}, и второй читать было бы
             * не из чего.
             */
            private BufferedReader reader;

            @Override
            public String line() {
                if (reader == null) {
                    reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                }
                try {
                    return reader.readLine();
                } catch (IOException e) {
                    throw new CliException(Msg.t("input.unreadable"), e);
                }
            }

            @Override
            public char[] secret(String prompt) {
                java.io.Console jvm = System.console();
                if (hidden() && jvm != null) {
                    char[] typed = jvm.readPassword("%s", console.adapt(prompt));
                    return typed == null ? new char[0] : typed;
                }
                console.prompt(prompt);
                String typed = line();
                return typed == null ? new char[0] : typed.toCharArray();
            }

            @Override
            public boolean hidden() {
                return console.isTerminal();
            }
        };
    }
}
