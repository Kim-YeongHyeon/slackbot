package com.jirabot.slack.util;

import java.util.LinkedHashMap;
import java.util.Map;

// STUDY: 버그 원인 카테고리 코드 ↔ Notion 표시 라벨 매핑을 한 곳에 모은다.
//        소분류를 추가/세분화할 때 여기 SUB 와 prompts/bug-category.md 만 함께 고치면 된다.
//        대분류 라벨은 "A 동시성·경쟁", 소분류 라벨은 "A1 머지race" 형태 (일회성 분류 적재와 동일).
public final class BugCategory {

    private BugCategory() {}

    private static final Map<Character, String> MAJOR = new LinkedHashMap<>();
    private static final Map<String, String> SUB = new LinkedHashMap<>();

    static {
        MAJOR.put('A', "A 동시성·경쟁");
        MAJOR.put('B', "B 복구·재시작");
        MAJOR.put('C', "C 키·암호");
        MAJOR.put('D', "D 빌드·CI·패키징");
        MAJOR.put('E', "E 리소스·성능");
        MAJOR.put('F', "F 데이터정합성");
        MAJOR.put('G', "G 검색·연산크래시");
        MAJOR.put('H', "H 외부연동");
        MAJOR.put('I', "I 보안");
        MAJOR.put('J', "J SDK·API");

        SUB.put("A1", "A1 머지race");      SUB.put("A2", "A2 샤드lifecycle");
        SUB.put("A3", "A3 deadlock/livelock"); SUB.put("A4", "A4 in-flight삭제");
        SUB.put("B1", "B1 docker재기동 로드실패"); SUB.put("B2", "B2 orchestrator복구");
        SUB.put("B3", "B3 fail recovery·retry");
        SUB.put("C1", "C1 keygen·serialize"); SUB.put("C2", "C2 register/release");
        SUB.put("C3", "C3 KMS·CGo");        SUB.put("C4", "C4 key load·path·token");
        SUB.put("D1", "D1 컴파일러·의존성");   SUB.put("D2", "D2 패키징");
        SUB.put("D3", "D3 SBOM·라이선스");    SUB.put("D4", "D4 CI·nightly");
        SUB.put("E1", "E1 메모리");          SUB.put("E2", "E2 CPU·병렬화");
        SUB.put("E3", "E3 DB연결");          SUB.put("E4", "E4 지연·타임아웃");
        SUB.put("F1", "F1 export/import");  SUB.put("F2", "F2 샤드데이터");
        SUB.put("F3", "F3 count·dim mismatch");
        SUB.put("G1", "G1 segfault");       SUB.put("G2", "G2 GPU");
        SUB.put("G3", "G3 insert·index오류");
        SUB.put("H1", "H1 TLS");            SUB.put("H2", "H2 presigned URL");
        SUB.put("H3", "H3 gRPC·네트워크");
        SUB.put("I1", "I1 CVE");            SUB.put("I2", "I2 SBOM critical");
        SUB.put("J1", "J1 직렬화·인터페이스");  SUB.put("J2", "J2 타입·차원");
    }

    /** 코드(A1 등)가 유효한 소분류인지. */
    public static boolean isValidSub(String code) {
        return code != null && SUB.containsKey(code);
    }

    /** 소분류 코드 → 대분류 라벨 ("A1" → "A 동시성·경쟁"). 모르면 null. */
    public static String majorLabel(String code) {
        if (code == null || code.isEmpty()) return null;
        return MAJOR.get(code.charAt(0));
    }

    /** 소분류 코드 → 라벨 ("A1" → "A1 머지race"). 미등록이면 코드 그대로. */
    public static String subLabel(String code) {
        return SUB.getOrDefault(code, code);
    }
}
