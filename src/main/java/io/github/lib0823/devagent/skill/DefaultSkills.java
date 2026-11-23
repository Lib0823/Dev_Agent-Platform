package io.github.lib0823.devagent.skill;

import java.util.List;

/**
 * 플랫폼이 처음 한 번 심어 주는 기본 스킬. 심은 뒤로는 사용자 것이다 — 고치거나 끄거나 지워도 플랫폼이 되돌리지 않는다
 * ({@link SkillService#seedDefaults()}).
 * <p>
 * 여기에 넣는 기준: ① 기본 프롬프트({@code IssuePrompts}·프리뷰 안내·{@code ResearchPrompts})에 이미 있는 내용은 넣지 않는다.
 * ② 팀마다 기준이 달라 고치거나 끄고 싶을 만한 것만. ③ 특정 저장소·도구(프리뷰 등)가 있어야만 지킬 수 있는 지침은 넣지 않는다.
 * ④ 적용 단계를 반드시 명시한다(비우면 전 단계라 플랫폼 관리 세션에도 실린다). ⑤ 본문은 인라인 상한(1,500자) 안에서 짧게 —
 * 해당 단계의 모든 실행에 실린다.
 */
final class DefaultSkills {

    private DefaultSkills() {
    }

    static List<AgentSkill> all() {
        return List.of(
                skill("regression-test",
                        "버그를 고치는 작업일 때(작업 유형 fix·hotfix) — 설계·수정·검증에서 재현 테스트를 다룬다",
                        "analyze,fix,verify",
                        """
                        기능 추가·리팩터링·문서 작업에는 적용하지 않습니다.
                        - 분석·설계: 저장소에 자동 테스트가 있는지(테스트 디렉터리, 빌드 설정의 테스트 작업) 먼저 확인합니다. 있으면 '## 수정 설계' 에 "이 버그를 재현하는 테스트 추가"를 항목으로 넣고 대상 테스트 파일을 적습니다. 없으면 테스트 기반을 새로 만들지 말고 '## 검증 방법' 에 수동 확인 절차를 구체적으로 적습니다.
                        - 코드 수정: 설계에 테스트 항목이 있으면, 수정 전 코드에서는 실패하고 수정 뒤에는 통과하는 테스트를 기존 테스트의 위치·이름·스타일에 맞춰 추가합니다. 그 테스트를 실행한 결과를 '## 검증 결과' 에 적고, 실행하지 못했으면 이유를 적습니다.
                        - 검증(리뷰어): 추가된 테스트가 이슈의 원인을 실제로 겨냥하는지(수정을 되돌리면 실패할지) 봅니다. 테스트가 없다는 것만으로 FAIL 로 판정하지 말고 근거에 적습니다.
                        """),
                skill("new-dependency",
                        "라이브러리·플러그인을 추가하거나 버전을 바꾸고 싶어질 때(pom.xml, build.gradle, package.json 같은 의존성 선언 변경)",
                        "analyze,fix",
                        """
                        - 먼저 저장소가 이미 쓰는 의존성과 언어 표준 라이브러리로 해결할 수 있는지 찾습니다.
                        - 그래도 필요하면 분석·설계 단계에서 '## 확인 필요' 에 무엇을(이름·버전), 왜 필요한지, 추가하지 않는 대안은 무엇인지 적어 사람의 승인을 받습니다.
                        - 승인된 설계에 없으면 수정 단계에서 의존성 선언을 바꾸지 않습니다. 뒤늦게 필요해지면 추가 없이 가능한 데까지만 하고 '## 변경 요약' 에 그 사실을 남깁니다.
                        - 기존 의존성의 버전 변경, 빌드 플러그인 추가도 같은 규칙입니다.
                        이유: 검증 빌드는 오프라인으로 돌 수 있어 로컬 저장소에 없는 라이브러리는 빌드가 실패하고, 라이선스·보안 검토는 사람이 해야 합니다.
                        """));
    }

    private static AgentSkill skill(String name, String description, String stages, String body) {
        AgentSkill s = new AgentSkill();
        s.setName(name);
        s.setDescription(description);
        s.setStages(stages);
        s.setBody(body.strip());
        return s;
    }
}
