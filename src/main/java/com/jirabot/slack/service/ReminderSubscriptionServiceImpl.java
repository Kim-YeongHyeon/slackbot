package com.jirabot.slack.service;

import com.jirabot.slack.config.ReminderProperties;
import com.jirabot.slack.entity.UserMappingEntity;
import com.jirabot.slack.repository.UserMappingRepository;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

// STUDY: 사용자별 리마인더 opt-in 토글. UserMappingEntity.reminderEnabled 한 컬럼으로 관리하고
//        매핑이 없는 사용자는 먼저 `@봇더지라 등록` 으로 매핑을 만들도록 안내한다.
//        enable/disable 은 상태 변경이 실제로 일어날 때만 DB save 를 호출한다 (반복 호출 시 불필요한 쓰기 회피).
@Service
public class ReminderSubscriptionServiceImpl implements ReminderSubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(ReminderSubscriptionServiceImpl.class);

    private final UserMappingRepository userMappingRepository;
    private final ReminderProperties reminderProps;

    public ReminderSubscriptionServiceImpl(UserMappingRepository userMappingRepository,
                                           ReminderProperties reminderProps) {
        this.userMappingRepository = userMappingRepository;
        this.reminderProps = reminderProps;
    }

    @Override
    public String enable(String slackUserId) {
        Optional<UserMappingEntity> mapping = userMappingRepository.findBySlackUserId(slackUserId);
        if (mapping.isEmpty()) {
            return ":warning: 먼저 `@봇더지라 등록 <Jira 사용자명>` 으로 본인 매핑을 등록해주세요.";
        }
        UserMappingEntity m = mapping.get();
        if (m.isReminderEnabled()) {
            // 멱등 — 이미 ON 이면 DB write 없이 동일 메시지를 회신.
            return ":bell: 리마인더가 이미 켜져 있습니다.";
        }
        m.setReminderEnabled(true);
        userMappingRepository.save(m);
        log.info("Reminder enabled slackUserId={}", slackUserId);
        return ":bell: 리마인더가 켜졌습니다. 평일 09:00 KST 에 미해결 이슈가 있으면 DM 으로 알려드립니다.";
    }

    @Override
    public String disable(String slackUserId) {
        Optional<UserMappingEntity> mapping = userMappingRepository.findBySlackUserId(slackUserId);
        if (mapping.isEmpty()) {
            // 매핑이 없으면 어차피 OFF 상태와 동일 — 멱등 안내.
            return ":no_bell: 리마인더가 꺼져 있습니다.";
        }
        UserMappingEntity m = mapping.get();
        if (!m.isReminderEnabled()) {
            // 이미 OFF 면 DB write 회피.
            return ":no_bell: 리마인더가 꺼져 있습니다.";
        }
        m.setReminderEnabled(false);
        userMappingRepository.save(m);
        log.info("Reminder disabled slackUserId={}", slackUserId);
        return ":no_bell: 리마인더가 꺼졌습니다.";
    }

    @Override
    public String status(String slackUserId) {
        Optional<UserMappingEntity> mapping = userMappingRepository.findBySlackUserId(slackUserId);
        boolean enabled = mapping.map(UserMappingEntity::isReminderEnabled).orElse(false);
        if (enabled) {
            return String.format(":bell: 리마인더 ON · 스케줄 `%s` (%s).",
                    reminderProps.cron(), reminderProps.zone());
        }
        if (mapping.isEmpty()) {
            return ":no_bell: 리마인더 OFF — 매핑 미등록 상태입니다. `@봇더지라 등록 <Jira 사용자명>` 으로 먼저 등록하세요.";
        }
        return ":no_bell: 리마인더 OFF.";
    }
}
