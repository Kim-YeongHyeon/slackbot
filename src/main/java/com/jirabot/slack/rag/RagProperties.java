package com.jirabot.slack.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

// STUDY: 문서 질문(`@지라 질문 <소스> <질문>`, v0.0.99) 설정. record + compact constructor 로 기본값을 채운다
//        (CiAlertProperties 와 같은 패턴). 검색은 같은 서버의 rag 사이드카(Python)가 하고, 봇은 HTTP 로만 묻는다.
@ConfigurationProperties(prefix = "rag")
public record RagProperties(
        Boolean enabled,
        // rag 사이드카 주소. 사이드카는 127.0.0.1 에만 열려 있다 (docker-compose 의 rag 서비스).
        String baseUrl,
        // 답변에 근거로 넘길 조각 수
        int topK,
        int searchTimeoutSeconds,
        // 조각 6개(약 1만 자)를 읽고 답을 쓰므로 분류용 claude.timeout-seconds(20초)보다 길게 둔다.
        int answerTimeoutSeconds,
        // 답변에 쓸 모델. 비면 claude.model.
        String model
) {
    public RagProperties {
        if (enabled == null) {
            enabled = true;
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://127.0.0.1:8090";
        }
        if (topK <= 0) {
            topK = 6;
        }
        if (searchTimeoutSeconds <= 0) {
            searchTimeoutSeconds = 20;
        }
        if (answerTimeoutSeconds <= 0) {
            answerTimeoutSeconds = 90;
        }
        if (model == null) {
            model = "";
        }
    }

    public String modelOr(String fallback) {
        return model.isBlank() ? fallback : model;
    }
}
