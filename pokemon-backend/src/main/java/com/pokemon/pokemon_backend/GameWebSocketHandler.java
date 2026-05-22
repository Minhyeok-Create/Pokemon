package com.pokemon.pokemon_backend;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class GameWebSocketHandler extends TextWebSocketHandler {

    // 💡 네트워크 연결이 활성화된 소켓 세션들을 관리하는 주머니
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @Autowired
    private PlayerRepository playerRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // 🗺️ 맵별 타일 데이터 (0: 잔디/이동가능, 1: 갈색벽/이동불가)
    private final int[][] townMap = {
            {0, 0, 0, 1, 1},
            {1, 1, 0, 1, 0},
            {0, 0, 0, 0, 0},
            {0, 1, 1, 1, 0},
            {0, 0, 0, 1, 0}
    };

    private final int[][] fieldMap = {
            {0, 0, 0, 0, 0},
            {0, 1, 1, 1, 0},
            {0, 0, 0, 0, 0},
            {0, 1, 0, 1, 0},
            {1, 1, 0, 1, 1}
    };

    private final int TILE_SIZE = 80;

    // 🟢 [입장] 웹소켓 네트워크가 최초 연결되었을 때
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = session.getId();
        sessions.put(sessionId, session);
        System.out.println("⚡ [네트워크 연결] 소켓 통신 개시 (세션 ID: " + sessionId + ")");
    }

    // 🟡 [통신] 프론트엔드에서 신호가 올 때마다 처리하는 메인 라우터
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        System.out.println("\n📥 [서버 수신 데이터] -> " + payload);

        try {
            Map<String, Object> requestData = objectMapper.readValue(payload, Map.class);
            String type = (String) requestData.get("type");

            if (type == null) {
                System.out.println("❌ 에러: 데이터에 type이 지정되지 않았습니다.");
                return;
            }

            // 📝 [A] 회원가입 처리 요청
            if ("SIGNUP".equals(type)) {
                String username = (String) requestData.get("username");
                String password = (String) requestData.get("password");

                Map<String, Object> signupResult = new HashMap<>();
                signupResult.put("type", "SIGNUP_RESULT");

                // 중복 가입 체크
                if (playerRepository.existsById(username)) {
                    signupResult.put("success", false);
                    signupResult.put("message", "이미 사용 중인 아이디입니다.");
                } else {
                    // 완전히 처음 온 유저라면 기본 좌표(180, 130)와 함께 기본 맵 'town' 지정 후 DB 가입 처리
                    Player newPlayer = new Player();
                    newPlayer.setId(username);
                    newPlayer.setPassword(password);
                    newPlayer.setX(180);
                    newPlayer.setY(130);
                    newPlayer.setMap("town"); // 🗺️ 기본 시작 맵 설정
                    playerRepository.save(newPlayer);

                    signupResult.put("success", true);
                    signupResult.put("message", "트레이너 등록이 완료되었습니다!");
                    System.out.println("🎉 [DB 회원가입 성공] 신규 유저 생성 -> ID: " + username);
                }
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(signupResult)));
            }

            // 🔑 [B] 로그인 처리 요청
            else if ("LOGIN".equals(type)) {
                String username = (String) requestData.get("username");
                String password = (String) requestData.get("password");

                Player player = playerRepository.findById(username).orElse(null);
                Map<String, Object> loginResult = new HashMap<>();

                if (player == null || !player.getPassword().equals(password)) {
                    loginResult.put("type", "LOGIN_RESULT");
                    loginResult.put("success", false);
                    loginResult.put("message", "아이디 또는 비밀번호가 올바르지 않습니다.");
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(loginResult)));
                    return;
                }

                session.getAttributes().put("username", username);
                System.out.println("🔓 [로그인 인증 완료] 트레이너 맵 진입 -> ID: " + username);

                // 💡 [DB 연동] 현재 모든 플레이어 목록 수집 시 각자의 세이브 맵 상태도 함께 보냅니다.
                Map<String, Object> currentPlayers = new HashMap<>();
                for (WebSocketSession s : sessions.values()) {
                    String activeUser = (String) s.getAttributes().get("username");

                    if (activeUser != null) {
                        playerRepository.findById(activeUser).ifPresent(p -> {
                            Map<String, Object> pData = new HashMap<>();
                            pData.put("x", p.getX());
                            pData.put("y", p.getY());
                            pData.put("map", p.getMap() != null ? p.getMap().toLowerCase() : "town"); // 🗺️ 유저별 맵 전송
                            currentPlayers.put(p.getId(), pData);
                        });
                    }
                }

                Map<String, Object> initData = new HashMap<>();
                initData.put("type", "INIT");
                initData.put("myId", username);
                initData.put("players", currentPlayers);
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(initData)));

                // 내가 어떤 맵으로 진입했는지 기본 상태 꺼내서 동기화 방송
                String initialMap = player.getMap() != null ? player.getMap().toLowerCase() : "town";
                sendBroadcastUpdate(username, player.getX(), player.getY(), initialMap);
            }

            // 🏃‍♂️ [C] 캐릭터 이동 요청 (멀티 맵 전환 완벽 지원 픽스본)
            else if ("MOVE".equals(type)) {
                String username = (String) session.getAttributes().get("username");
                if (username == null) return;

                int nextX = ((Number) requestData.get("x")).intValue();
                int nextY = ((Number) requestData.get("y")).intValue();

                // 프론트엔드가 요구하는 목적지 맵 이름 수집
                String clientMap = (String) requestData.get("map");
                if (clientMap == null) clientMap = "town";
                String safeClientMap = clientMap.toLowerCase();

                boolean canMove = true;

                Player player = playerRepository.findById(username).orElse(null);
                if (player != null) {
                    String currentDbMap = player.getMap() != null ? player.getMap().toLowerCase() : "town";

                    // 🚪 [맵 전환 게이트 활성화] 프론트가 요구하는 타겟 맵과 DB의 현재 세이브 맵이 다르다면?
                    // 경계선을 지나 워프하는 긴급 상태이므로 타일 충돌 계산 없이 무조건 통과시킵니다.
                    if (!currentDbMap.equals(safeClientMap)) {
                        canMove = true;
                        System.out.println("🚪 [맵 전환 통과] 유저 [" + username + "] -> " + currentDbMap + " 에서 " + safeClientMap + "(으)로 전환");
                    }
                    // 🧱 [일반 무빙] 같은 맵 내부에서 꼼지락거릴 때만 정밀 타일 벽 충돌 체크
                    else {
                        int tileX = (nextX + 32) / TILE_SIZE;
                        int tileY = (nextY + 32) / TILE_SIZE;

                        // 맵 경계선 및 해당 맵의 벽 구조 체크
                        if (tileX < 0 || tileX >= 5 || tileY < 0 || tileY >= 5) {
                            canMove = false;
                        } else {
                            int[][] targetMapArr = "field".equals(safeClientMap) ? fieldMap : townMap;
                            if (targetMapArr[tileY][tileX] == 1) {
                                canMove = false;
                            }
                        }
                    }
                }

                // 💡 이동이 가능할 때 쿼리를 1순위로 즉시 강제 커밋(Flush)합니다.
                if (canMove) {
                    final int finalX = nextX;
                    final int finalY = nextY;
                    final String finalMap = safeClientMap;

                    playerRepository.findById(username).ifPresent(p -> {
                        p.setX(finalX);
                        p.setY(finalY);
                        p.setMap(finalMap); // 🗺️ 최신 데이터베이스 맵 업데이트
                        playerRepository.saveAndFlush(p); // 👈 영속성 캐시 무력화 직후 실시간 쓰기
                    });
                }

                // 💡 최종 가공이 승인 완료된 최신 확정 데이터 기준 동기화 브로드캐스트 송출
                Player currentState = playerRepository.findById(username).orElse(null);
                if (currentState != null) {
                    String savedMap = currentState.getMap() != null ? currentState.getMap().toLowerCase() : "town";
                    sendBroadcastUpdate(username, currentState.getX(), currentState.getY(), savedMap);
                }
            }
            else if ("CHAT".equals(type)) {
                String username = (String) session.getAttributes().get("username");
                if (username == null) return;

                String msg = (String) requestData.get("msg");
                System.out.println("[채팅]" + username + " : " + msg);

                Map<String, Object> chatData = new HashMap<>();
                chatData.put("type", "CHAT_MESSAGE");
                chatData.put("id", username);
                chatData.put("msg", msg);

                String jsonResponse = objectMapper.writeValueAsString(chatData);
                for (WebSocketSession s : sessions.values()) {
                    if (s.isOpen()) {
                        s.sendMessage(new TextMessage(jsonResponse));
                    }
                }
            }

        } catch (Exception e) {
            System.out.println("💥 [자바 내부 에러 발생] 원인: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // 🔴 [퇴장] 플레이어가 브라우저를 닫거나 나갔을 때
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String sessionId = session.getId();
        sessions.remove(sessionId);

        String username = (String) session.getAttributes().get("username");
        System.out.println("❌ [퇴장] 네트워크 소켓 종료 (세션 ID: " + sessionId + " | 로그인 유저: " + username + ")");

        if (username != null) {
            Map<String, Object> removeData = new HashMap<>();
            removeData.put("type", "REMOVE");
            removeData.put("id", username);

            String jsonResponse = objectMapper.writeValueAsString(removeData);
            for (WebSocketSession s : sessions.values()) {
                if (s.isOpen()) {
                    s.sendMessage(new TextMessage(jsonResponse));
                }
            }
        }
    }

    // 📡 [공통 방송용 헬퍼 메서드] 맵 동기화를 위해 map 인자값을 추가 수용합니다.
    private void sendBroadcastUpdate(String username, int x, int y, String mapName) throws Exception {
        Map<String, Object> responseData = new HashMap<>();
        responseData.put("type", "UPDATE");
        responseData.put("id", username);
        responseData.put("x", x);
        responseData.put("y", y);
        responseData.put("map", mapName.toLowerCase()); // 🗺️ 프론트엔드로 실시간 맵 전송

        String jsonResponse = objectMapper.writeValueAsString(responseData);
        for (WebSocketSession s : sessions.values()) {
            if (s.isOpen()) {
                s.sendMessage(new TextMessage(jsonResponse));
            }
        }
    }
}