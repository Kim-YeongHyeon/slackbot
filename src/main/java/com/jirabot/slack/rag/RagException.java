package com.jirabot.slack.rag;

import java.util.List;

// STUDY: 사이드카 호출 실패를 종류별로 나눈다 — 사용자에게 보여 줄 안내가 종류마다 다르기 때문이다.
public class RagException extends RuntimeException {

    public enum Kind {
        /** sources.yml 에 없는 소스 이름. sources() 에 쓸 수 있는 소스가 들어 있다. */
        UNKNOWN_SOURCE,
        /** 소스는 있지만 아직 데이터를 만들지 않았거나 읽지 못했다. */
        NOT_READY,
        /** 질문이 비었거나 너무 길다 등 요청이 잘못됐다. */
        BAD_REQUEST,
        /** 사이드카가 꺼져 있거나 응답하지 않는다. */
        UNAVAILABLE
    }

    private final Kind kind;
    private final List<String> sources;

    public RagException(Kind kind, String message, List<String> sources) {
        super(message);
        this.kind = kind;
        this.sources = sources == null ? List.of() : List.copyOf(sources);
    }

    public RagException(Kind kind, String message) {
        this(kind, message, List.of());
    }

    public Kind kind() {
        return kind;
    }

    public List<String> sources() {
        return sources;
    }
}
