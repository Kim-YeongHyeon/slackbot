package com.jirabot.slack.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NotionPropertyTest {

    @SuppressWarnings("unchecked")
    @Test
    void title_buildsTitleArrayWithContent() {
        Map<String, Object> p = NotionProperty.title("ES2-7 로그인 에러");
        List<?> arr = (List<?>) p.get("title");
        Map<String, Object> first = (Map<String, Object>) arr.get(0);
        Map<String, Object> text = (Map<String, Object>) first.get("text");
        assertThat(text.get("content")).isEqualTo("ES2-7 로그인 에러");
    }

    @Test
    void richText_blankOrNull_isEmptyArray() {
        assertThat((List<?>) NotionProperty.richText(null).get("rich_text")).isEmpty();
        assertThat((List<?>) NotionProperty.richText("").get("rich_text")).isEmpty();
    }

    @SuppressWarnings("unchecked")
    @Test
    void richText_truncatesAt2000() {
        String big = "x".repeat(2500);
        Map<String, Object> p = NotionProperty.richText(big);
        List<?> arr = (List<?>) p.get("rich_text");
        Map<String, Object> text = (Map<String, Object>) ((Map<String, Object>) arr.get(0)).get("text");
        assertThat(((String) text.get("content")).length()).isEqualTo(2000);
    }

    @Test
    void url_nullOrBlank_isNullValue() {
        assertThat(NotionProperty.url(null).get("url")).isNull();
        assertThat(NotionProperty.url("  ").get("url")).isNull();
        assertThat(NotionProperty.url("https://x/browse/ES2-7").get("url")).isEqualTo("https://x/browse/ES2-7");
    }

    @SuppressWarnings("unchecked")
    @Test
    void date_setOrCleared() {
        assertThat(NotionProperty.date(null).get("date")).isNull();
        assertThat(NotionProperty.date("").get("date")).isNull();
        Map<String, Object> d = (Map<String, Object>) NotionProperty.date("2026-06-01").get("date");
        assertThat(d.get("start")).isEqualTo("2026-06-01");
    }

    @SuppressWarnings("unchecked")
    @Test
    void select_setOrCleared() {
        assertThat(NotionProperty.select(null).get("select")).isNull();
        Map<String, Object> s = (Map<String, Object>) NotionProperty.select("해결").get("select");
        assertThat(s.get("name")).isEqualTo("해결");
    }
}
