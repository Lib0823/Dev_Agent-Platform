package io.github.lib0823.devagent.workspace;

import io.github.lib0823.devagent.admin.SettingService;
import io.github.lib0823.devagent.agent.CodingAgent;
import io.github.lib0823.devagent.config.WorkspaceProperties;
import io.github.lib0823.devagent.gitlab.IssueEventBus;
import io.github.lib0823.devagent.llm.UsageService;
import io.github.lib0823.devagent.mcp.McpConfigService;
import io.github.lib0823.devagent.messenger.MessengerService;
import io.github.lib0823.devagent.notify.NotificationService;
import io.github.lib0823.devagent.skill.SkillService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 메신저 명령(@AI 작업 …)이 작업 공간 요청·상태·확인 답으로 이어지는지. 세션은 띄우지 않는다(차례를 찾지 못하면 작업 스레드가 바로 끝난다). */
class WorkspaceMessengerTest {

    @TempDir
    Path tmp;

    private WorkspaceMapper mapper;
    private WorkspaceService service;
    private Workspace paper;
    private Workspace infra;

    @BeforeEach
    void setUp() {
        mapper = mock(WorkspaceMapper.class);
        UsageService usage = mock(UsageService.class);
        service = new WorkspaceService(new WorkspaceProperties(true, "", "", "", 0, 0, 0, 0), mapper, mock(CodingAgent.class),
                mock(IssueEventBus.class), mock(SettingService.class), usage, mock(NotificationService.class), mock(McpConfigService.class),
                mock(SkillService.class), new WorkspaceApprovals(), mock(MessengerService.class), 8777, tmp.resolve("work").toString());
        paper = ws(1, "paper");
        infra = ws(2, "infra");
        when(mapper.workspaces()).thenReturn(List.of(infra, paper));
        when(mapper.findWorkspace(1)).thenReturn(paper);
        when(mapper.findWorkspace(2)).thenReturn(infra);
    }

    private Workspace ws(long id, String name) {
        Workspace w = new Workspace();
        w.setId(id);
        w.setName(name);
        w.setPath(tmp.toString());
        w.setMode(Workspace.BYPASS);
        return w;
    }

    @Test
    void listsWorkspacesAndUsageWhenNothingIsAsked() {
        String r = service.messengerCommand("", false);
        assertTrue(r.contains("infra, paper"), r);
        assertTrue(r.contains("@AI 작업 <작업 공간>: <요청>"), r);
        assertEquals("진행 중인 요청이 없습니다.", service.messengerCommand("상태", false));
        assertEquals("기다리는 실행 전 확인이 없습니다.", service.messengerCommand("허용", false));
        assertEquals("진행 중인 요청이 없습니다.", service.messengerCommand("중단", false));
    }

    @Test
    void submitsToTheNamedWorkspaceContinuingItsLastThread() {
        WorkspaceTurn first = new WorkspaceTurn();
        first.setId(5);
        first.setWorkspaceId(1);
        first.setThreadId(5);
        when(mapper.lastThread(1)).thenReturn(5L);
        when(mapper.find(5)).thenReturn(first);

        String r = service.messengerCommand("paper: 3장 표를 최신 로그로 채워 줘", false);
        assertTrue(r.contains("「paper」 접수 (이어서"), r);
        ArgumentCaptor<WorkspaceTurn> turn = ArgumentCaptor.forClass(WorkspaceTurn.class);
        verify(mapper).insert(turn.capture());
        assertEquals(1, turn.getValue().getWorkspaceId());
        assertEquals(5, turn.getValue().getThreadId());
        assertEquals("3장 표를 최신 로그로 채워 줘", turn.getValue().getQuestion());
        assertEquals(WorkspaceService.ORIGIN_MESSENGER, turn.getValue().getOrigin());
    }

    @Test
    void startsNewThreadWhenAskedAndRejectsUnknownOrAmbiguousNames() {
        String r = service.messengerCommand("inf: 디스크 사용량 확인", true);
        assertTrue(r.contains("「infra」 접수 (새 대화"), r);
        verify(mapper, never()).lastThread(2);

        assertTrue(service.messengerCommand("없는곳: 뭐든", false).contains("작업 공간을 찾을 수 없습니다"));
        // 작업 공간이 둘인데 이름이 없으면 고르지 않는다(엉뚱한 폴더에서 일하지 않게)
        assertTrue(service.messengerCommand("테스트 돌려 줘", false).contains("어느 작업 공간인지"));
    }

    @Test
    void resultMessageCarriesAnswerHeadAndLink() {
        WorkspaceTurn t = new WorkspaceTurn();
        t.setId(9);
        t.setWorkspaceId(1);
        t.setThreadId(5);
        t.setState(WorkspaceTurn.DONE);
        t.setElapsedMs(125_000);
        t.setAnswerMd("표를 다시 채웠습니다. ".repeat(300));
        String m = service.messengerResult(t, paper, 2);
        assertTrue(m.startsWith("[작업] paper 완료 · 2분 · 바뀐 파일 2"), m);
        assertTrue(m.endsWith("/workspace.html#w=1&t=5"), m);
        assertTrue(m.length() < 2100, "길면 앞부분만: " + m.length());
        assertNull(t.getLastError());
    }
}
