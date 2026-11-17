package io.github.lib0823.devagent.docs;

/**
 * 문서 섹션 1개 (MkDocs search_index.json 의 doc 1개 = 한 heading 블록).
 * body 는 HTML 태그를 제거한 평문으로 저장한다.
 */
public class DocChunk {

    private Long id;
    private String location;   // MkDocs location (예: "application/.../#heading", 언어는 "ko/" prefix)
    private String url;        // 딥링크 = siteBase + location
    private String title;      // 섹션 제목
    private String body;       // 평문 본문
    private String lang;       // ko | en (vi 는 적재 제외)
    private int score;         // 검색 결과 점수(조회 전용)

    public DocChunk() {
    }

    public DocChunk(String location, String url, String title, String body, String lang) {
        this.location = location;
        this.url = url;
        this.title = title;
        this.body = body;
        this.lang = lang;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getLang() {
        return lang;
    }

    public void setLang(String lang) {
        this.lang = lang;
    }

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = score;
    }
}
