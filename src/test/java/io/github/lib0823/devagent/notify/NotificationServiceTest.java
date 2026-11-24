package io.github.lib0823.devagent.notify;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NotificationServiceTest {

    @Test
    void 이슈_안내문은_첫_줄이_제목_나머지가_본문() {
        Notification n = NotificationService.issue("[이슈] 분석 시작: shop#12 — 로그인 오류\n소스를 받아 원인/설계를 정리합니다.");
        assertEquals("issue", n.getKind());
        assertEquals("info", n.getLevel());
        assertEquals("분석 시작: shop#12 — 로그인 오류", n.getTitle());
        assertEquals("소스를 받아 원인/설계를 정리합니다.", n.getBody());
        assertEquals("/agent.html", n.getLink());
    }

    @Test
    void 확인을_기다리는_알림은_wait() {
        assertEquals("wait", NotificationService.issueLevel("설계안 shop#12 — 로그인 오류"));
        assertEquals("wait", NotificationService.issueLevel("수정 완료 shop#12"));
        assertEquals("wait", NotificationService.issueLevel("⏰ shop#12 설계 확인을 3시간째 기다리고 있습니다.".replace("⏰ ", "")));
        // 검증 시작 안내는 확인 대기가 아니다
        assertEquals("info", NotificationService.issueLevel("수정 완료 — 검증 1회차 (shop#12): 빌드·정적검사 후 독립 리뷰어가 확인합니다."));
    }

    @Test
    void 실패_경고_완료_수준() {
        assertEquals("err", NotificationService.issueLevel("실패 shop#12 (FIXING)"));
        assertEquals("err", NotificationService.issueLevel("⚠️ 푸시 실패 shop#12"));
        assertEquals("warn", NotificationService.issueLevel("⚠ Claude 사용량 한도에 도달했습니다 (리셋 14:20)."));
        assertEquals("warn", NotificationService.issueLevel("중단됨 shop#12 (사용자 요청)."));
        assertEquals("ok", NotificationService.issueLevel("완료 shop#12 — 로그인 오류"));
    }

    @Test
    void 되받아치기와_분할_이어쓰기는_알림이_아니다() {
        assertNull(NotificationService.issue("[이슈] (관리 페이지 답변) 진행"));
        assertNull(NotificationService.issue("[이슈] (계속 2/3)\n나머지"));
        assertNull(NotificationService.issue("  "));
    }

    @Test
    void issueNoticeLinksToItsSessionWhenTheTextNamesAnIssue() {
        // 안내문에 group/repo#번호 가 있으면 그 세션으로(에이전트 화면이 #ref= 를 푼다), 없으면 대시보드
        assertEquals("/agent.html#ref=grp%2Fsub%2Frepo%2312", NotificationService.issueLink("설계안 grp/sub/repo#12 — 로그인 오류\n본문"));
        assertEquals("/agent.html#ref=g%2Fr%237", NotificationService.issueLink("분석 시작: g/r#7 — 제목"));
        assertEquals("/agent.html", NotificationService.issueLink("진행 중인 이슈: 없음"));
        assertEquals("/agent.html", NotificationService.issueLink(null));
    }
}
