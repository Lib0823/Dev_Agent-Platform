package io.github.lib0823.devagent.ops;

import io.github.lib0823.devagent.messenger.MessengerService;
import io.github.lib0823.devagent.messenger.Room;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 유지보수 도구: {@code --list-rooms} 인자로 실행하면 방 목록을 "방키  이름 [종류]" 형태로 출력하고 종료한다.
 * 화이트리스트(bot.mention.auto-room-keys)에 넣을 room-key 를 찾을 때 사용.
 * 예) java -jar dev-agent.jar --list-rooms
 */
@Component
@Order(0)
public class RoomListRunner implements ApplicationRunner {

    private final MessengerService client;

    public RoomListRunner(MessengerService client) {
        this.client = client;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("list-rooms")) {
            return;
        }
        client.connect();
        List<Room> rooms = client.rooms();
        System.out.println("\n===== 방 목록 (room-key  이름  [종류, 인원]) =====");
        rooms.stream()
                .sorted((a, b) -> a.type().compareTo(b.type()))
                .forEach(r -> System.out.printf("%-16s %s  [%s%s]%n",
                        r.key(),
                        r.name(),
                        r.type(),
                        r.memberCount() != null ? ", " + r.memberCount() + "명" : ""));
        System.out.println("===== 끝 — 위 room-key 를 .env 의 BOT_AUTO_ROOMS 에 넣으면 그 방 자동응답(AUTO) =====\n");
        System.out.flush();
        System.exit(0);
    }
}
