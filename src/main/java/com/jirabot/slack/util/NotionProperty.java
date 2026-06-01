package com.jirabot.slack.util;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// STUDY: Notion page property 값 객체를 타입별로 조립한다. Notion 은 빈/누락 값을 null 로 보내야 비우므로
//        Map.of(불변, null 불가) 대신 HashMap 을 쓴다. rich_text 한 블록은 최대 2000자 제한이라 잘라낸다.
public final class NotionProperty {

    private static final int MAX_TEXT = 2000;

    private NotionProperty() {}

    public static Map<String, Object> title(String text) {
        Map<String, Object> m = new HashMap<>();
        m.put("title", List.of(Map.of("text", Map.of("content", truncate(text)))));
        return m;
    }

    public static Map<String, Object> richText(String text) {
        Map<String, Object> m = new HashMap<>();
        String t = text == null ? "" : truncate(text);
        if (t.isEmpty()) {
            m.put("rich_text", List.of());
        } else {
            m.put("rich_text", List.of(Map.of("text", Map.of("content", t))));
        }
        return m;
    }

    public static Map<String, Object> url(String url) {
        Map<String, Object> m = new HashMap<>();
        m.put("url", (url == null || url.isBlank()) ? null : url);
        return m;
    }

    // STUDY: isoDate 는 "2026-06-01" 또는 RFC3339 datetime. null/blank 이면 날짜를 비운다.
    public static Map<String, Object> date(String isoDate) {
        Map<String, Object> m = new HashMap<>();
        if (isoDate == null || isoDate.isBlank()) {
            m.put("date", null);
        } else {
            m.put("date", Map.of("start", isoDate));
        }
        return m;
    }

    public static Map<String, Object> select(String name) {
        Map<String, Object> m = new HashMap<>();
        m.put("select", (name == null || name.isBlank()) ? null : Map.of("name", name));
        return m;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) : s;
    }
}
