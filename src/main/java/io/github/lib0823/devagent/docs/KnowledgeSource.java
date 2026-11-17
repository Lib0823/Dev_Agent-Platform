package io.github.lib0823.devagent.docs;

import io.github.lib0823.devagent.platform.PortHealth;

import java.util.Map;

/**
 * 지식 검색(RAG) 포트. 질문과 관련된 사내 지식을 찾아 프롬프트에 주입할 컨텍스트로 돌려준다.
 * 코어({@code @AI} 답변·멘션 초안·이슈 분석)는 이 인터페이스만 알고, 구현체는 {@code platform.knowledge} 값으로 하나만 활성화된다.
 * <p>
 * 현재 구현체 {@code mkdocs-keyword}({@link DocRetrievalService}): MkDocs 가 빌드한 search_index.json 을 Postgres 에 적재하고
 * 키워드 점수로 찾는다(임베딩 없음, 가볍고 빠르지만 표현이 다르면 못 찾는다).
 * <p>
 * 벡터 RAG 로 바꿀 때: 이 인터페이스를 구현한 어댑터를 추가한다(예: {@code pgvector}).
 * <ol>
 *   <li>적재: 문서를 의미 단위(제목 경로 + 300~800자, 약간 겹치게)로 자르고 임베딩해 같은 Postgres 의 pgvector 컬럼에 저장한다.
 *       출처 URL·제목 경로·갱신 시각을 함께 둔다. 적재는 핫패스 밖(스케줄)에서 한다.</li>
 *   <li>검색: 질문을 임베딩해 벡터 유사도 상위 N 을 뽑고, 지금의 키워드 점수와 합쳐(하이브리드) 상위 K 를 고른다.
 *       사내 용어·식별자는 키워드가, 의미가 같은 다른 표현은 벡터가 잡는다.</li>
 *   <li>게이트: 최고 점수가 임계치 미만이면 null 을 돌려 주입을 건너뛴다(지금과 같은 규약 — 관련 없는 문서를 넣지 않는다).</li>
 *   <li>임베딩 모델: API(예: Voyage, OpenAI)나 로컬 모델(Ollama bge-m3 등)을 어댑터 안에서만 쓴다. 코어는 모른다.</li>
 * </ol>
 */
public interface KnowledgeSource {

    /** 구현체 이름. 예: mkdocs-keyword, pgvector */
    String provider();

    /** 연결 상태(즉시 응답): 적재된 양, 마지막 적재 결과 등. */
    PortHealth health();

    /** 질문과 관련된 지식을 프롬프트 주입용 블록으로. 관련된 것이 없거나 실패하면 null(호출측 응답을 막지 않는다). */
    String retrieveContext(String query);

    /** 검색 결과 상세(관리 화면·에이전트의 문서 검색 API 용): hits[].title/url/score 등. */
    Map<String, Object> debug(String query, int k);
}
