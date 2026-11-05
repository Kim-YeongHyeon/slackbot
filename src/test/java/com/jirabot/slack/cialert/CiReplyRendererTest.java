package com.jirabot.slack.cialert;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CiReplyRendererTest {

    private static final String RUN = "https://github.com/CryptoLabInc/evi/actions/runs/1";

    @Test
    void analysis_rendersAllSections() {
        CiAnalysis a = new CiAnalysis("INFRA", "high", "프록시 DNS 조회 실패예요.", "코드 변경과 무관해요.",
                List.of("lookup proxy: i/o timeout"), List.of("러너 DNS 확인 후 re-run"));

        String text = CiReplyRenderer.analysis("evi", "evi main", a, "3번 연속 실패",
                "c1d2b07d1234", "dev", RUN);

        assertThat(text)
                .startsWith("🤖 *CI 실패 분석* · evi · evi main")
                .contains("*분류* 🏗️ 인프라·러너 (신뢰도 높음) · 3번 연속 실패")
                .contains("*원인* 프록시 DNS 조회 실패예요.")
                .contains("> `lookup proxy: i/o timeout`")
                .contains("• 러너 DNS 확인 후 re-run")
                .endsWith("_커밋 c1d2b07d · dev · <" + RUN + "|run 보기>_");
    }

    @Test
    void analysis_escapesSlackControlCharactersFromModelOutput() {
        CiAnalysis a = new CiAnalysis("CODE", "low", "<!channel> 확인 & <https://evil|여기>",
                null, List.of("a `tick` b"), List.of());

        String text = CiReplyRenderer.analysis("evi", "wf", a, "", "abc", "", RUN);

        assertThat(text).contains("&lt;!channel&gt; 확인 &amp; &lt;https://evil|여기&gt;")
                .doesNotContain("<!channel>")
                .contains("> `a 'tick' b`")
                .doesNotContain("*다음 조치*");
    }

    @Test
    void analysis_unknownConfidenceAndEmptyStatus_areOmitted() {
        CiAnalysis a = new CiAnalysis("UNKNOWN", "???", "로그가 부족해요.", "", List.of(), List.of());

        String text = CiReplyRenderer.analysis("evi", "wf", a, "", "abc", null, RUN);

        assertThat(text).contains("*분류* ❓ 판단 어려움\n").doesNotContain("신뢰도").doesNotContain("*근거*");
    }

    @Test
    void repeat_withAndWithoutPermalink() {
        assertThat(CiReplyRenderer.repeat("11/5 15:03", "https://slack/p1", "DNS 실패"))
                .isEqualTo("🔁 11/5 15:03 알림과 같은 실패예요 → <https://slack/p1|이전 분석>\n*원인* DNS 실패");
        assertThat(CiReplyRenderer.repeat("11/5 15:03", null, null))
                .isEqualTo("🔁 11/5 15:03 알림과 같은 실패예요 → 이전 분석");
    }

    @Test
    void failure_escapesReason() {
        assertThat(CiReplyRenderer.failure("HTTP <403>", RUN))
                .isEqualTo("⚠️ 자동 분석에 실패했어요: HTTP &lt;403&gt; — <" + RUN + "|run 로그>를 직접 확인해주세요.");
    }
}
