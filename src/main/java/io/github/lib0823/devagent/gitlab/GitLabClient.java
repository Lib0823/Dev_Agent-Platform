package io.github.lib0823.devagent.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lib0823.devagent.config.GitLabProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GitLab REST API v4 최소 클라이언트 (PAT 인증).
 * 프로젝트 조회 / 라벨 이슈 목록 / 이슈 단건 / 이슈 등록 / 이슈 노트 / 코멘트 작성 / MR 생성.
 */
@Component
public class GitLabClient {

    private static final Logger log = LoggerFactory.getLogger(GitLabClient.class);

    private final GitLabProperties props;
    private final RestClient rest;

    public GitLabClient(RestClient.Builder builder, GitLabProperties props) {
        this.props = props;
        String base = props.url() == null ? "http://localhost" : props.url().replaceAll("/+$", "");
        this.rest = builder.baseUrl(base + "/api/v4")
                .defaultHeader("PRIVATE-TOKEN", props.token() == null ? "" : props.token())
                .build();
    }

    /** 프로젝트 정보: id, default_branch, http_url_to_repo, web_url, path_with_namespace. */
    public record Project(long id, String path, String defaultBranch, String httpUrl, String webUrl) {
    }

    public record Issue(long projectId, long iid, String title, String description, String webUrl,
                        List<String> labels, String author, String createdAt) {
        public String ref(String projectPath) {
            return projectPath + "#" + iid;
        }
    }

    public Project project(String path) {
        // 경로 변수는 Spring 이 '/' 포함 엄격 인코딩(%2F) → 그대로 전달
        JsonNode p = rest.get().uri("/projects/{id}", path).retrieve().body(JsonNode.class);
        if (p == null || !p.hasNonNull("id")) {
            throw new IllegalStateException("GitLab 프로젝트 조회 실패: " + path);
        }
        return new Project(p.get("id").asLong(), p.path("path_with_namespace").asText(path),
                p.path("default_branch").asText("main"), p.path("http_url_to_repo").asText(""),
                p.path("web_url").asText(props.projectUrl(path)));
    }

    /** 원격에 브랜치가 있는지(컨벤션 기준 브랜치 develop/main 존재 확인용). 404 → false. */
    public boolean branchExists(long projectId, String branch) {
        if (branch == null || branch.isBlank()) {
            return false;
        }
        try {
            JsonNode b = rest.get().uri("/projects/{p}/repository/branches/{b}", projectId, branch).retrieve().body(JsonNode.class);
            return b != null && b.hasNonNull("name");
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            return false;
        }
    }

    /** 브랜치 이름 목록(최근 갱신 순, 최대 100개). search 가 있으면 이름에 포함된 것만. 질의 탭의 브랜치 선택용. */
    public List<String> branches(long projectId, String search) {
        JsonNode arr = rest.get().uri(b -> {
            b.path("/projects/{p}/repository/branches").queryParam("per_page", 100).queryParam("sort", "updated_desc");
            if (search != null && !search.isBlank()) {
                b.queryParam("search", search.strip());
            }
            return b.build(projectId);
        }).retrieve().body(JsonNode.class);
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            arr.forEach(n -> out.add(n.path("name").asText()));
        }
        return out;
    }

    /** 라벨이 붙은 열린 이슈 (오래된 순). label 이 비어 있으면 열린 이슈 전부. */
    public List<Issue> openIssues(long projectId, String label) {
        String lbl = label == null ? "" : label.strip();
        JsonNode arr = rest.get().uri(b -> {
            b.path("/projects/{p}/issues").queryParam("state", "opened")
                    .queryParam("order_by", "created_at").queryParam("sort", "asc").queryParam("per_page", 50);
            if (!lbl.isBlank()) {
                b.queryParam("labels", "{l}");
            }
            return lbl.isBlank() ? b.build(projectId) : b.build(projectId, lbl);
        }).retrieve().body(JsonNode.class);
        List<Issue> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                out.add(toIssue(n));
            }
        }
        return out;
    }

    public Issue issue(long projectId, long iid) {
        JsonNode n = rest.get().uri("/projects/{p}/issues/{i}", projectId, iid).retrieve().body(JsonNode.class);
        if (n == null || !n.hasNonNull("iid")) {
            throw new IllegalStateException("GitLab 이슈 조회 실패: project=" + projectId + " iid=" + iid);
        }
        return toIssue(n);
    }

    /** 이슈 등록(플랫폼 화면의 "새 이슈"). labels 는 쉼표 구분, 비어 있으면 라벨 없이. */
    public Issue createIssue(long projectId, String title, String description, String labels) {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("title", title);
        form.put("description", description == null ? "" : description);
        if (labels != null && !labels.isBlank()) {
            form.put("labels", labels.strip());
        }
        JsonNode n = rest.post().uri("/projects/{p}/issues", projectId)
                .contentType(MediaType.APPLICATION_JSON).body(form)
                .retrieve().body(JsonNode.class);
        if (n == null || !n.hasNonNull("iid")) {
            throw new IllegalStateException("GitLab 이슈 등록 실패: project=" + projectId);
        }
        log.info("GitLab 이슈 등록 project={} iid={}", projectId, n.get("iid").asLong());
        return toIssue(n);
    }

    /** 이슈 코멘트(시스템 노트 제외) 본문 목록 — 분석 맥락으로 사용. */
    public List<String> issueNotes(long projectId, long iid, int limit) {
        JsonNode arr = rest.get()
                .uri("/projects/{p}/issues/{i}/notes?sort=asc&order_by=created_at&per_page={n}", projectId, iid, limit)
                .retrieve().body(JsonNode.class);
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                if (n.path("system").asBoolean(false)) {
                    continue;
                }
                String author = n.path("author").path("name").asText("?");
                out.add(author + ": " + n.path("body").asText(""));
            }
        }
        return out;
    }

    public void comment(long projectId, long iid, String body) {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("body", body);
        rest.post().uri("/projects/{p}/issues/{i}/notes", projectId, iid)
                .contentType(MediaType.APPLICATION_JSON).body(form)
                .retrieve().body(JsonNode.class);
        log.info("GitLab 이슈 코멘트 작성 project={} iid={}", projectId, iid);
    }

    /** Draft MR 생성. 반환: MR web_url. */
    public String createMergeRequest(long projectId, String source, String target, String title, String description) {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("source_branch", source);
        form.put("target_branch", target);
        form.put("title", title);
        form.put("description", description);
        form.put("remove_source_branch", true);
        JsonNode mr = rest.post().uri("/projects/{p}/merge_requests", projectId)
                .contentType(MediaType.APPLICATION_JSON).body(form)
                .retrieve().body(JsonNode.class);
        String url = mr == null ? "" : mr.path("web_url").asText("");
        log.info("GitLab MR 생성 project={} {} -> {} url={}", projectId, source, target, url);
        return url;
    }

    private static Issue toIssue(JsonNode n) {
        List<String> labels = new ArrayList<>();
        if (n.path("labels").isArray()) {
            n.path("labels").forEach(l -> labels.add(l.asText()));
        }
        return new Issue(n.path("project_id").asLong(), n.path("iid").asLong(),
                n.path("title").asText(""), n.path("description").asText(""),
                n.path("web_url").asText(""), labels,
                n.path("author").path("name").asText("?"), n.path("created_at").asText(""));
    }
}
