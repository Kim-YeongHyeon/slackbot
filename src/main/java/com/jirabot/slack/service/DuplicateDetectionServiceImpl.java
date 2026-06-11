package com.jirabot.slack.service;

import com.jirabot.slack.entity.IssueEntity;
import com.jirabot.slack.entity.StatusCategory;
import com.jirabot.slack.repository.IssueRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// STUDY: 2-pass 설계의 1단계(DB 키워드 필터). 제목에서 의미 있는 키워드를 추출하고
//        각 키워드로 DB 검색 → 후보 합산 → 중복 제거. Claude 비교(2단계)는 추후 확장.
@Service
@Transactional(readOnly = true)
public class DuplicateDetectionServiceImpl implements DuplicateDetectionService {

    private static final Logger log = LoggerFactory.getLogger(DuplicateDetectionServiceImpl.class);
    private static final int MIN_KEYWORD_LENGTH = 2;
    private static final int MAX_RESULTS = 5;

    // STUDY: 한국어 조사/접미사, 영어 불용어 등 의미 없는 단어를 제외해야 검색 정확도가 올라간다.
    private static final Set<String> STOP_WORDS = Set.of(
            "에서", "에서의", "으로", "되는", "하는", "있는", "없는", "된", "할", "를", "이", "가",
            "the", "a", "an", "is", "are", "in", "on", "at", "for", "to", "of", "and", "or",
            "추가", "수정", "변경", "필요", "관련", "대한", "해주세요", "부탁", "합니다", "있습니다"
    );

    private static final Pattern SPLIT_PATTERN = Pattern.compile("[\\s/\\-_.,()\\[\\]{}]+");

    private final IssueRepository issueRepository;

    public DuplicateDetectionServiceImpl(IssueRepository issueRepository) {
        this.issueRepository = issueRepository;
    }

    // STUDY: 키워드 1개만 겹치면 오탐이 많다 (예: "페이지"만으로 모든 페이지 관련 이슈 매칭).
    //        최소 2개 이상의 키워드가 겹쳐야 유사 이슈로 판정한다.
    private static final int MIN_KEYWORD_MATCHES = 2;

    @Override
    public List<IssueEntity> findSimilar(String title) {
        if (title == null || title.isBlank()) {
            return List.of();
        }

        List<String> keywords = extractKeywords(title);
        if (keywords.size() < MIN_KEYWORD_MATCHES) {
            return List.of();
        }

        // STUDY: 기존엔 키워드마다 LIKE 쿼리(5~10회 풀스캔)를 날렸다. 미완료 이슈를 1회만 가져와
        //        Java 에서 키워드 포함 수를 집계 — 쿼리 1회로 동일 결과(LOWER LIKE = lowercase contains).
        List<IssueEntity> candidates = issueRepository.findByStatusCategoryNot(StatusCategory.DONE);

        record Scored(IssueEntity issue, int matches) {}
        List<IssueEntity> result = candidates.stream()
                .map(issue -> {
                    String summaryLower = issue.getSummary() == null ? "" : issue.getSummary().toLowerCase();
                    int matches = (int) keywords.stream().filter(summaryLower::contains).count();
                    return new Scored(issue, matches);
                })
                .filter(s -> s.matches() >= MIN_KEYWORD_MATCHES)
                .sorted((a, b) -> Integer.compare(b.matches(), a.matches()))
                .limit(MAX_RESULTS)
                .map(Scored::issue)
                .toList();

        log.debug("Duplicate check for '{}': keywords={} candidates={}", title, keywords, result.size());
        return result;
    }

    private List<String> extractKeywords(String title) {
        String[] tokens = SPLIT_PATTERN.split(title.strip().toLowerCase());
        List<String> keywords = new ArrayList<>();
        for (String token : tokens) {
            if (token.length() >= MIN_KEYWORD_LENGTH && !STOP_WORDS.contains(token)) {
                keywords.add(token);
            }
        }
        return keywords;
    }
}
