package io.github.lib0823.devagent.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lib0823.devagent.config.DocsProperties;
import io.github.lib0823.devagent.ops.AlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 사내 문서(MkDocs) 적재기. search_index.json 을 받아 섹션 단위로 doc_chunk 에 전면 갱신(full refresh)한다.
 * 적재는 핫패스 밖에서만 수행(기동 1회 + 스케줄) — 메신저 응답 경로에 영향을 주지 않는다.
 */
@Service
public class DocIngestService {

    private static final Logger log = LoggerFactory.getLogger(DocIngestService.class);
    private static final int BATCH = 500;

    private final DocsProperties props;
    private final DocChunkMapper mapper;
    private final TransactionTemplate tx;
    private final AlertService alerts;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile OffsetDateTime lastRefreshAt;
    private volatile String lastResult = "아직 실행 안 됨";

    public DocIngestService(DocsProperties props, DocChunkMapper mapper,
                            PlatformTransactionManager txManager, AlertService alerts) {
        this.props = props;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(txManager);
        this.alerts = alerts;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!props.enabled()) {
            log.info("문서 RAG 비활성(docs.enabled=false) — 적재 생략");
            return;
        }
        try {
            mapper.ensureTable();
        } catch (Exception e) {
            log.warn("doc_chunk 테이블 보장 실패: {}", e.toString());
        }
        if (props.refreshOnStartup()) {
            // 기동을 막지 않도록 백그라운드로 적재
            Thread t = new Thread(this::refreshQuietly, "doc-ingest-startup");
            t.setDaemon(true);
            t.start();
        }
    }

    /** 주기 적재. docs.refresh-cron 기준. 비활성이면 no-op. */
    @Scheduled(cron = "${docs.refresh-cron}")
    public void scheduledRefresh() {
        if (!props.enabled()) {
            return;
        }
        refreshQuietly();
    }

    /** 예외를 삼키고 상태만 기록하는 적재(스케줄/기동/수동 공용). */
    public void refreshQuietly() {
        try {
            int n = refresh();
            lastResult = "성공: " + n + "개 섹션";
        } catch (Exception e) {
            lastResult = "실패: " + e;
            log.warn("문서 적재 실패: {}", e.toString());
            alerts.alert("문서 적재 실패", e); // 쿨다운 적용(자기방 알림)
        }
    }

    /** 인덱스를 받아 전면 갱신하고 적재된 섹션 수를 반환. */
    public int refresh() throws Exception {
        if (!running.compareAndSet(false, true)) {
            log.info("문서 적재가 이미 진행 중 — 이번 호출 skip");
            return (int) mapper.countAll();
        }
        try {
            long t0 = System.currentTimeMillis();
            byte[] raw = download(props.searchIndexUrl());
            List<DocChunk> chunks = parse(raw);
            if (chunks.isEmpty()) {
                throw new IllegalStateException("파싱된 섹션이 0개 — 적재 중단(기존 데이터 유지)");
            }
            replaceAll(chunks);
            lastRefreshAt = OffsetDateTime.now();
            long ms = System.currentTimeMillis() - t0;
            log.info("문서 적재 완료: {}개 섹션 ({}ms) ← {}", chunks.size(), ms, props.searchIndexUrl());
            return chunks.size();
        } finally {
            running.set(false);
        }
    }

    private void replaceAll(List<DocChunk> chunks) {
        tx.executeWithoutResult(status -> {
            mapper.ensureTable();
            mapper.deleteAll();
            for (int i = 0; i < chunks.size(); i += BATCH) {
                mapper.insertBatch(chunks.subList(i, Math.min(i + BATCH, chunks.size())));
            }
        });
    }

    private byte[] download(String url) throws Exception {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.fetchTimeoutMs()))
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(props.fetchTimeoutMs()))
                .GET()
                .build();
        HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() != 200) {
            throw new IllegalStateException("search_index.json HTTP " + res.statusCode());
        }
        return res.body();
    }

    private List<DocChunk> parse(byte[] raw) throws Exception {
        JsonNode root = json.readTree(raw);
        JsonNode docs = root.path("docs");
        List<DocChunk> out = new ArrayList<>();
        if (!docs.isArray()) {
            return out;
        }
        String base = props.siteBaseUrl();
        for (JsonNode d : docs) {
            String location = d.path("location").asText("");
            String lang = langOf(location);
            if ("vi".equals(lang)) {
                continue; // 베트남어 제외
            }
            String title = clean(d.path("title").asText(""));
            String body = clean(d.path("text").asText(""));
            if (body.length() < 40) {
                continue; // 앵커만 있고 본문이 빈약한 섹션 제외
            }
            if (body.length() > props.maxBodyChars()) {
                body = body.substring(0, props.maxBodyChars());
            }
            out.add(new DocChunk(location, base + location, title.isBlank() ? location : title, body, lang));
        }
        return out;
    }

    private static String langOf(String location) {
        if (location.startsWith("ko/")) {
            return "ko";
        }
        if (location.startsWith("vi/")) {
            return "vi";
        }
        return "en";
    }

    /** HTML 태그 제거 + 엔티티 디코드 + 공백 정리 → 평문. */
    private static String clean(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        String s = html.replaceAll("<[^>]+>", " ");
        s = s.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        return s.replaceAll("\\s+", " ").trim();
    }

    public OffsetDateTime getLastRefreshAt() {
        return lastRefreshAt;
    }

    public String getLastResult() {
        return lastResult;
    }

    public long count() {
        try {
            return mapper.countAll();
        } catch (Exception e) {
            return -1;
        }
    }
}
