package io.hammerhall.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Один разборщик вместо четырёх (ревью CLI №1, АРХ-1).
 *
 * <p>Каждый случай с пометкой — поломка 0.1.4, найденная ревью
 * воспроизведением: заплатки в командах ломались от пробела после
 * двоеточия, от массива верхнего уровня, путали ключ со значением
 * и обрезали дробное. Здесь они — навсегда под тестом.
 */
@DisplayName("Json — разбор и запись")
class JsonTest {

    @Nested
    @DisplayName("🔴 поломки 0.1.4, найденные ревью")
    class Regressions {

        @Test
        @DisplayName("ВЫС-1: пробелы и переносы — вложенный объект читается так же, как слитный")
        void prettyPrinted() {
            String compact = "{\"status\":\"FAILED\",\"tests\":{\"total\":5,\"passed\":3}}";
            String pretty = """
                    {
                      "status" : "FAILED",
                      "tests" : { "total" : 5, "passed" : 3 }
                    }""";
            for (String text : List.of(compact, pretty)) {
                Json.Node tests = Json.parse(text).get("tests");
                assertThat(tests.get("total").asLong(0)).isEqualTo(5);
                assertThat(tests.get("passed").asLong(0)).isEqualTo(3);
            }
        }

        @Test
        @DisplayName("ВЫС-1: объект внутри объекта не обрезается на первой «}»")
        void nestedDeeper() {
            Json.Node tests = Json.parse("{\"tests\":{\"detail\":{\"x\":1},\"total\":7}}").get("tests");
            assertThat(tests.get("total").asLong(0)).isEqualTo(7);
        }

        @Test
        @DisplayName("ВЫС-2: массив верхнего уровня — читается")
        void topLevelArray() {
            Json.Node list = Json.parse("[{\"status\":\"PASSED\"},{\"status\":\"FAILED\"}]");
            assertThat(list.isArray()).isTrue();
            assertThat(list.items()).extracting(n -> n.get("status").string())
                    .containsExactly("PASSED", "FAILED");
        }

        @Test
        @DisplayName("ВЫС-3: строковое значение, равное имени поля, — не поле")
        void valueIsNotKey() {
            Json.Node node = Json.parse("{\"log\":\"status\",\"code\":42}");
            assertThat(node.get("status").isMissing()).isTrue();
            assertThat(node.get("log").string()).isEqualTo("status");
        }

        @Test
        @DisplayName("СР-12: дробное не режется")
        void fractions() {
            Json.Node node = Json.parse("{\"points\":12.5,\"e\":1.5e2,\"neg\":-0.25}");
            assertThat(node.get("points").asDouble(0)).isEqualTo(12.5);
            assertThat(node.get("e").asDouble(0)).isEqualTo(150.0);
            assertThat(node.get("neg").asDouble(0)).isEqualTo(-0.25);
            assertThat(node.get("points").asLong(0)).isEqualTo(13);   // округление, а не отсечение
        }
    }

    @Test
    @DisplayName("строки: экранирование, \\u, суррогатная пара — эмодзи целиком")
    void strings() {
        Json.Node node = Json.parse("{\"a\":\"кавычка \\\" слэш \\\\ \\/ \\n\\t\\r\\b\\f\",\"e\":\"\\ud83d\\udd25\",\"u\":\"\\u0416\"}");
        assertThat(node.get("a").string()).isEqualTo("кавычка \" слэш \\ / \n\t\r\b\f");
        assertThat(node.get("e").string()).isEqualTo("🔥");
        assertThat(node.get("u").string()).isEqualTo("Ж");
    }

    @Test
    @DisplayName("литералы и навигация: нет поля, не тот тип — MISSING, а не исключение")
    void literalsAndMissing() {
        Json.Node node = Json.parse("{\"t\":true,\"f\":false,\"n\":null,\"s\":\"x\",\"arr\":[\"a\",1,\"b\"]}");
        assertThat(node.get("t").asBool(false)).isTrue();
        assertThat(node.get("f").asBool(true)).isFalse();
        assertThat(node.get("n").isMissing()).isFalse();
        assertThat(node.get("n").string()).isNull();
        assertThat(node.get("nope").get("deeper").asLong(-1)).isEqualTo(-1);
        assertThat(node.get("s").asLong(7)).isEqualTo(7);
        assertThat(node.get("s").get("x").isMissing()).isTrue();
        assertThat(node.get("arr").strings()).containsExactly("a", "b");
        assertThat(node.get("s").items()).isEmpty();
        assertThat(node.get("s").keys()).isEmpty();
        assertThat(node.get("arr").isObject()).isFalse();
        assertThat(node.isObject()).isTrue();
        assertThat(Json.parse("{\"b\":\"  \"}").get("b").text()).isNull();
    }

    @Test
    @DisplayName("ключи объекта — в порядке ответа сервера")
    void keyOrder() {
        assertThat(Json.parse("{\"SEQ\":1,\"JOIN\":2,\"ALGO\":3}").keys()).containsExactly("SEQ", "JOIN", "ALGO");
    }

    @Test
    @DisplayName("пустые объект и массив")
    void empties() {
        assertThat(Json.parse(" { } ").keys()).isEmpty();
        assertThat(Json.parse("[ ]").items()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "{\"a\"}", "{\"a\":1,}", "[1 2]", "{\"a\":tru}", "\"не закрыта",
            "{\"a\":1} лишнее", "{a:1}", "{\"a\":\"\\x\"}", "{\"a\":\"\\u12\"}", "{\"a\":\"\\uzzzz\"}",
            "{\"a\":1-2}", "@", "{\"a\":\"перевод\nстроки\"}", "[1,", "{\"a\":1"})
    @DisplayName("испорченный JSON — понятная ошибка с позицией, а не «прочитал сколько смог»")
    void malformed(String text) {
        assertThatThrownBy(() -> Json.parse(text))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON");
    }

    @Test
    @DisplayName("null вместо текста — ошибка")
    void nullText() {
        assertThatThrownBy(() -> Json.parse(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Nested
    @DisplayName("запись")
    class Writing {

        @Test
        @DisplayName("ВЫС-9: экранируется каждое значение — и номер, и кузница, не только код")
        void everyValueEscaped() {
            String body = Json.object("forgeId", "a\"b", "taskId", "x\\y", "code", "line\n\"q\"\u0001");
            Json.Node back = Json.parse(body);
            assertThat(back.get("forgeId").string()).isEqualTo("a\"b");
            assertThat(back.get("taskId").string()).isEqualTo("x\\y");
            assertThat(back.get("code").string()).isEqualTo("line\n\"q\"\u0001");
            assertThat(body).contains("\\u0001");
        }

        @Test
        @DisplayName("дерево туда и обратно")
        void roundTrip() {
            Map<String, Object> tree = new LinkedHashMap<>();
            tree.put("n", null);
            tree.put("b", true);
            tree.put("i", 42);
            tree.put("l", List.of("a", 1, false));
            tree.put("m", Map.of("k", "v"));
            String text = Json.write(tree);
            assertThat(text).isEqualTo("{\"n\":null,\"b\":true,\"i\":42,\"l\":[\"a\",1,false],\"m\":{\"k\":\"v\"}}");
            assertThat(Json.parse(text).compact()).isEqualTo(text);
            assertThat(Json.Node.MISSING.compact()).isEmpty();
        }

        @Test
        @DisplayName("нечётные пары и незнакомый тип — ошибка разработчика, а не молчание")
        void misuse() {
            assertThatThrownBy(() -> Json.object("a")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Json.write(new Object())).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
