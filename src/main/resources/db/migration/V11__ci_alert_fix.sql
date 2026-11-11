-- 연속 실패 자동 수정 (v0.0.100). 같은 워크플로·브랜치(fix_key)에 수정 단계를 언제, 어떤 결과로 돌렸는지 남긴다.
-- 24시간 안에 다시 실패 알림이 와도 Opus 에이전트를 또 돌리지 않고 이 결과를 다시 보여 주는 데 쓴다.
ALTER TABLE ci_alerts ADD COLUMN fix_key    VARCHAR(300);
ALTER TABLE ci_alerts ADD COLUMN fix_status VARCHAR(20);
ALTER TABLE ci_alerts ADD COLUMN fix_detail TEXT;

CREATE INDEX idx_ci_alerts_fix ON ci_alerts (channel, fix_key, created_at);
