package io.github.lib0823.devagent.skill;

import io.github.lib0823.devagent.config.GitLabProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 스킬 검증, 단계별 프롬프트 주입(짧으면 본문, 길면 파일 참조), 파일 동기화. */
class SkillServiceTest {

    private static AgentSkill skill(String name, String desc, String body, String stages) {
        AgentSkill s = new AgentSkill();
        s.setName(name);
        s.setDescription(desc);
        s.setBody(body);
        s.setStages(stages);
        return s;
    }

    @Test
    void validatesNameDescriptionBody() {
        assertNotNull(SkillService.validate(skill("Bad Name", "d", "b", null)));
        assertNotNull(SkillService.validate(skill("ok-name", " ", "b", null)));
        assertNotNull(SkillService.validate(skill("ok-name", "d", " ", null)));
        AgentSkill ok = skill("commit-convention", "커밋 메시지를  쓸 때", "  본문  ", "fix, nope ,verify");
        assertNull(SkillService.validate(ok));
        assertEquals("커밋 메시지를 쓸 때", ok.getDescription());
        assertEquals("본문", ok.getBody());
        assertEquals("fix,verify", ok.getStages());
    }

    @Test
    void promptNoteFiltersByStageAndInlinesShortBodies(@TempDir Path tmp) throws Exception {
        AgentSkillMapper mapper = mock(AgentSkillMapper.class);
        GitLabProperties gitlab = mock(GitLabProperties.class);
        when(gitlab.workDir()).thenReturn(tmp.toString());
        AgentSkill shortFix = skill("short-fix", "수정할 때", "짧은 지침", "fix");
        AgentSkill longAll = skill("long-all", "항상", "가".repeat(SkillService.INLINE_MAX + 1), null);
        AgentSkill off = skill("off", "꺼짐", "x", "fix");
        off.setEnabled(false);
        when(mapper.findAll()).thenReturn(List.of(shortFix, longAll, off));
        SkillService svc = new SkillService(mapper, gitlab);
        svc.init();

        String fix = svc.promptNote("fix");
        assertTrue(fix.contains("스킬: short-fix") && fix.contains("짧은 지침"));
        assertTrue(fix.contains("스킬: long-all") && fix.contains("SKILL.md"));       // 긴 본문은 파일 참조
        assertFalse(fix.contains("가".repeat(50)));
        assertFalse(fix.contains("스킬: off"));

        String research = svc.promptNote("research");
        assertFalse(research.contains("short-fix"));                                    // fix 전용
        assertTrue(research.contains("long-all"));                                      // 단계 미지정 = 전 단계

        assertTrue(Files.readString(svc.file("long-all")).startsWith("---\nname: long-all"));
        assertEquals("", new SkillService(mock(AgentSkillMapper.class), gitlab).promptNote("fix"));
    }
}
