package io.hammerhall.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Самописный разбор JSON — самое хрупкое место клиента.
 *
 * <h3>Почему именно он</h3>
 *
 * Это единственный кусок клиента, который МОЛЧА возвращает не то. Ошибётся
 * `ForgeRoot` — человек увидит внятное «это не похоже на кузницу». Ошибётся
 * `ApiClient` — увидит текст ошибки. А промахнувшийся разбор поля просто
 * отдаст {@code null} или чужое значение, и вердикт напечатается неправильным
 * без единого признака поломки.
 *
 * <h3>Что охраняется</h3>
 *
 * Главный инвариант — <b>глубина</b>. Поле {@code "status"} встречается и на
 * верхнем уровне, и внутри {@code "tests"}; без учёта вложенности разбор взял
 * бы первое попавшееся. Второй инвариант — <b>строки</b>: имя поля, упомянутое
 * внутри текста лога, не должно сойти за поле.
 */
@DisplayName("Json — чтение полей верхнего уровня")
class JsonTest {

    /** Ответ платформы на /submit — форма, ради которой класс и написан. */
    private static final String VERDICT = """
            {"taskId":"MovCode0002",
             "status":"PASSED",
             "durationMs":1007,
             "fromHistory":false,
             "tests":{"total":10,"passed":10,"failed":0,"status":"ВЛОЖЕННЫЙ"},
             "failures":[],
             "log":"тут упоминается \\"status\\" внутри текста"}
            """;

    @Nested
    @DisplayName("строки")
    class Strings {

        @Test
        @DisplayName("поле верхнего уровня читается")
        void readsTopLevel() {
            assertThat(Json.string(VERDICT, "status")).isEqualTo("PASSED");
            assertThat(Json.string(VERDICT, "taskId")).isEqualTo("MovCode0002");
        }

        @Test
        @DisplayName("🔴 одноимённое поле ВНУТРИ вложенного объекта не подменяет верхнее")
        void nestedFieldDoesNotShadow() {
            // "status" есть и в корне, и в "tests". Без учёта глубины разбор
            // вернул бы вложенный — и вердикт напечатался бы неправильным.
            assertThat(Json.string(VERDICT, "status")).isEqualTo("PASSED");
        }

        @Test
        @DisplayName("🔴 имя поля внутри текста лога полем не считается")
        void fieldNameInsideStringIgnored() {
            String json = """
                    {"log":"строка со словом \\"status\\" внутри","status":"FAILED"}
                    """;
            assertThat(Json.string(json, "status")).isEqualTo("FAILED");
        }

        @Test
        @DisplayName("экранирование разворачивается")
        void unescapes() {
            String json = """
                    {"log":"перенос\\nи кавычка \\" и таб\\tконец"}
                    """;
            assertThat(Json.string(json, "log"))
                    .isEqualTo("перенос\nи кавычка \" и таб\tконец");
        }

        @Test
        @DisplayName("нет поля — null; поле не строка — null")
        void missingOrWrongTypeIsNull() {
            assertThat(Json.string(VERDICT, "нетТакого")).isNull();
            assertThat(Json.string(VERDICT, "durationMs")).isNull();
        }
    }

    @Nested
    @DisplayName("числа и флаги")
    class Numbers {

        @Test
        @DisplayName("число читается, отрицательное тоже")
        void readsNumber() {
            assertThat(Json.number(VERDICT, "durationMs", -1)).isEqualTo(1007);
            assertThat(Json.number("""
                    {"delta":-42}""", "delta", 0)).isEqualTo(-42);
        }

        @Test
        @DisplayName("нет поля или не число — отдаётся запасное значение")
        void fallsBack() {
            assertThat(Json.number(VERDICT, "нетТакого", 7)).isEqualTo(7);
            assertThat(Json.number(VERDICT, "status", 7)).isEqualTo(7);
        }

        @Test
        @DisplayName("флаг читается отдельным методом — строковый разбор его не видит")
        void readsBool() {
            assertThat(Json.bool(VERDICT, "fromHistory", true)).isFalse();
            assertThat(Json.bool("""
                    {"fromHistory":true}""", "fromHistory", false)).isTrue();
            assertThat(Json.bool(VERDICT, "нетТакого", true)).isTrue();
        }
    }

    @Nested
    @DisplayName("массивы")
    class Arrays {

        @Test
        @DisplayName("массив строк читается, пустой даёт пустой список")
        void readsArray() {
            String json = """
                    {"forges":["stream-forge","reactive-forge"],"failures":[]}
                    """;
            assertThat(Json.strings(json, "forges"))
                    .containsExactly("stream-forge", "reactive-forge");
            assertThat(Json.strings(json, "failures")).isEmpty();
        }

        @Test
        @DisplayName("нет поля — пустой список, а не null")
        void missingArrayIsEmpty() {
            assertThat(Json.strings(VERDICT, "нетТакого")).isEmpty();
        }
    }

    @Nested
    @DisplayName("экранирование на отправку")
    class Escaping {

        @Test
        @DisplayName("🔴 круг: экранировали код задачи → прочитали обратно тем же")
        void roundTrip() {
            // Ровно то, что делает submit: файл с кавычками, переносами и
            // табами уезжает в тело запроса и обязан вернуться собой.
            String code = "class A {\n\tString s = \"строка\\с экраном\";\r\n}";

            String json = "{\"code\":\"" + Json.escape(code) + "\"}";

            assertThat(Json.string(json, "code")).isEqualTo(code);
        }

        @Test
        @DisplayName("управляющие символы уходят в \\u-форму")
        void escapesControlChars() {
            assertThat(Json.escape("a\u0001b")).isEqualTo("a\\u0001b");
        }
    }

    @Test
    @DisplayName("массив объектов: поле верхнего уровня после него всё ещё находится")
    void survivesArrayOfObjects() {
        String json = """
                {"failures":[{"test":"a","status":"ВЛОЖЕННЫЙ"},{"test":"b"}],
                 "status":"FAILED"}
                """;
        assertThat(Json.string(json, "status")).isEqualTo("FAILED");
        assertThat(Json.strings(json, "нетТакого")).isEmpty();
    }
}
