package io.github.lib0823.devagent.workspace;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 「확인 후 실행」 작업 공간의 승인 대기열(메모리). 세션이 파일 수정·명령 실행을 하려 하면 CLI 가 확인 도구(MCP)를 부르고,
 * 그 호출은 여기서 사용자가 화면에서 허용/거부할 때까지 기다린다.
 * <ul>
 *   <li>차례마다 토큰 하나({@link #open}): 확인 도구의 주소에 들어가며, 토큰으로 어느 차례의 요청인지 안다. 끝나면 {@link #close}.</li>
 *   <li>「이번 요청 동안 계속 허용」은 도구 이름 단위로 그 차례가 끝날 때까지만 기억한다.</li>
 *   <li>재시작하면 대기 중이던 확인은 사라진다(세션도 함께 끊기므로 문제없다).</li>
 * </ul>
 */
@Component
public class WorkspaceApprovals {

    /** 사용자의 답. message 는 거부할 때 세션에 전할 말(선택). */
    public record Decision(boolean allow, String message) {
    }

    /** 화면에 보여 줄 대기 항목. */
    public record Pending(long id, long turnId, String tool, String summary, String detail, long createdAt) {
    }

    private record Entry(Pending pending, CompletableFuture<Decision> answer) {
    }

    private static final int MAX_PENDING_PER_TURN = 4;
    private final Map<String, Long> tokens = new ConcurrentHashMap<>();
    private final Map<Long, Entry> waiting = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> always = new ConcurrentHashMap<>();

    /** 차례의 확인 창구를 연다. 돌려주는 토큰이 확인 도구 주소에 들어간다. */
    public String open(long turnId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        tokens.put(token, turnId);
        return token;
    }

    /** 차례가 끝났다: 토큰을 없애고 남은 대기는 거부로 닫는다. */
    public void close(long turnId) {
        tokens.values().removeIf(v -> v == turnId);
        always.remove(turnId);
        waiting.values().removeIf(e -> {
            if (e.pending().turnId() == turnId) {
                e.answer().complete(new Decision(false, "요청이 끝났습니다."));
                return true;
            }
            return false;
        });
    }

    public Long turnOf(String token) {
        return token == null ? null : tokens.get(token);
    }

    /**
     * 확인을 요청하고 답을 기다린다(확인 도구 호출 스레드에서). 모르는 토큰이면 거부.
     *
     * @param timeoutMs 이 시간 안에 답이 없으면 거부로 처리
     */
    public Decision await(String token, String tool, String summary, String detail, long timeoutMs) {
        Long turnId = turnOf(token);
        if (turnId == null) {
            return new Decision(false, "확인 창구가 닫혔습니다.");
        }
        Set<String> ok = always.get(turnId);
        if (ok != null && ok.contains(tool)) {
            return new Decision(true, null);
        }
        // 한 차례에 동시에 기다릴 수 있는 확인 수를 묶는다: 기다리는 동안 요청 스레드를 하나씩 잡으므로(서버 스레드 16개) 병렬 도구 호출이 화면까지 멈추게 하지 않도록
        if (waiting.values().stream().filter(x -> x.pending().turnId() == turnId).count() >= MAX_PENDING_PER_TURN) {
            return new Decision(false, "확인을 기다리는 실행이 너무 많습니다. 하나씩 차례로 실행하세요.");
        }
        // 추측할 수 없는 id(다른 프로세스가 번호를 세어 허용 요청을 흉내 내지 못하게)
        long id;
        do {
            id = ThreadLocalRandom.current().nextLong(1L, 1L << 52);
        } while (waiting.containsKey(id));
        Entry e = new Entry(new Pending(id, turnId, tool, summary, detail, System.currentTimeMillis()), new CompletableFuture<>());
        waiting.put(id, e);
        if (turnOf(token) == null) {                  // 줄 서는 사이에 차례가 끝났다(close 와 엇갈림)
            waiting.remove(id);
            return new Decision(false, "요청이 끝났습니다.");
        }
        try {
            return e.answer().get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            return new Decision(false, "사용자가 답하지 않아 실행하지 않았습니다.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new Decision(false, "확인을 기다리다 중단되었습니다.");
        } catch (ExecutionException ex) {
            return new Decision(false, "확인 처리 오류");
        } finally {
            waiting.remove(id);
        }
    }

    /**
     * 사용자의 답을 전한다.
     *
     * @param remember 허용일 때 같은 도구를 이 차례가 끝날 때까지 다시 묻지 않는다
     * @return 대기 항목이 있었으면 true
     */
    public boolean decide(long id, boolean allow, boolean remember, String message) {
        Entry e = waiting.get(id);
        if (e == null) {
            return false;
        }
        if (allow && remember) {
            always.computeIfAbsent(e.pending().turnId(), k -> ConcurrentHashMap.newKeySet()).add(e.pending().tool());
            // 같은 도구로 이미 줄 서 있는 요청도 함께 푼다
            for (Entry o : new ArrayList<>(waiting.values())) {
                if (o != e && o.pending().turnId() == e.pending().turnId() && o.pending().tool().equals(e.pending().tool())) {
                    o.answer().complete(new Decision(true, null));
                }
            }
        }
        return e.answer().complete(new Decision(allow, message == null || message.isBlank() ? null : message.strip()));
    }

    /** 이 차례에서 답을 기다리는 항목(오래된 것부터). */
    public List<Pending> pending(long turnId) {
        return waiting.values().stream().map(Entry::pending).filter(p -> p.turnId() == turnId)
                .sorted(Comparator.comparingLong(Pending::createdAt)).toList();
    }

    /** 가장 오래 기다린 확인(메신저의 「허용/거부」가 답할 대상). 없으면 null. */
    public Pending oldest() {
        return waiting.values().stream().map(Entry::pending).min(Comparator.comparingLong(Pending::createdAt)).orElse(null);
    }

    /** 답을 기다리는 차례가 있는가(목록의 표시용): 차례 id → 대기 수. */
    public Map<Long, Integer> waitingTurns() {
        Map<Long, Integer> m = new LinkedHashMap<>();
        waiting.values().forEach(e -> m.merge(e.pending().turnId(), 1, Integer::sum));
        return m;
    }
}
