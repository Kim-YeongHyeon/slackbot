package com.jirabot.slack.client.process;

// STUDY: claude -p --output-format json 의 result 문자열에서 JSON 오브젝트만 꺼낸다.
//        모델이 ```json 펜스나 앞뒤 설명을 붙여도 파싱되게 하는 방어 코드.
public final class ClaudeJson {

    private ClaudeJson() {}

    public static String stripToJsonObject(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.strip();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl >= 0) {
                s = s.substring(nl + 1);
            }
            if (s.endsWith("```")) {
                s = s.substring(0, s.length() - 3);
            }
            s = s.strip();
        }
        if (!s.startsWith("{")) {
            int open = s.indexOf('{');
            int close = s.lastIndexOf('}');
            if (open >= 0 && close > open) {
                s = s.substring(open, close + 1);
            }
        }
        return s;
    }
}
