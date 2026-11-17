package io.github.lib0823.devagent.docs;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface DocChunkMapper {

    /** 기존(이미 초기화된) DB 에도 적용되도록 기동 시 멱등 DDL 로 테이블 보장. */
    @Update("""
            CREATE TABLE IF NOT EXISTS doc_chunk (
                id         BIGSERIAL    PRIMARY KEY,
                location   TEXT         NOT NULL UNIQUE,
                url        TEXT         NOT NULL,
                title      TEXT         NOT NULL,
                body       TEXT         NOT NULL,
                lang       VARCHAR(8),
                updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
            )
            """)
    void ensureTable();

    @Delete("DELETE FROM doc_chunk")
    void deleteAll();

    /** 여러 섹션을 한 문장으로 적재(청크 단위 호출). */
    @Insert("""
            <script>
            INSERT INTO doc_chunk (location, url, title, body, lang) VALUES
            <foreach collection="list" item="c" separator=",">
                (#{c.location}, #{c.url}, #{c.title}, #{c.body}, #{c.lang})
            </foreach>
            ON CONFLICT (location) DO NOTHING
            </script>
            """)
    int insertBatch(@Param("list") List<DocChunk> list);

    /** 한 페이지의 모든 섹션(location 이 "page/" 또는 "page/#heading"). 에이전트의 문서 읽기(/api/docs-read)용. */
    @Select("""
            SELECT id, location, url, title, body, lang, 0 AS score FROM doc_chunk
            WHERE location = #{page} OR location LIKE #{page} || '#%'
            ORDER BY id ASC
            """)
    List<DocChunk> pageSections(@Param("page") String page);

    @Select("SELECT count(*) FROM doc_chunk")
    long countAll();

    /**
     * 질문 토큰(쉼표구분)으로 섹션을 스코어링해 상위 topK 반환.
     * 점수 = (제목에 등장한 토큰 수) * 3 + (본문에 등장한 토큰 수). 대소문자 무시(한글은 무영향).
     * position() 사용으로 ILIKE 의 %,_ 이스케이프 문제를 피한다. termsCsv 는 호출부에서 반드시 비어있지 않게 보장.
     */
    @Select("""
            SELECT id, location, url, title, body, lang,
                   (SELECT count(*) FROM unnest(string_to_array(#{termsCsv}, ',')) AS t
                      WHERE position(lower(t) in lower(title)) > 0) * 3
                 + (SELECT count(*) FROM unnest(string_to_array(#{termsCsv}, ',')) AS t
                      WHERE position(lower(t) in lower(body)) > 0) AS score
            FROM doc_chunk
            WHERE EXISTS (SELECT 1 FROM unnest(string_to_array(#{termsCsv}, ',')) AS t
                           WHERE position(lower(t) in lower(title)) > 0
                              OR position(lower(t) in lower(body)) > 0)
            ORDER BY score DESC, char_length(body) ASC
            LIMIT #{topK}
            """)
    List<DocChunk> search(@Param("termsCsv") String termsCsv, @Param("topK") int topK);
}
