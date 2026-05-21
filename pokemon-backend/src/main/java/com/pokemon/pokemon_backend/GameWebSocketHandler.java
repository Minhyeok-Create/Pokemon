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

    // 🗺️ 기존 게임 맵 및 타일 설정 유지
    private final int[][] gameMap = {
            {0, 0, 0, 1, 1},
            {1, 1, 0, 1, 0},
            {0, 0, 0, 0, 0},
            {0, 1, 1, 1, 0},
            {0, 0, 0, 1, 0}
    };
    private final int TILE_SIZE = 80;

    // 🟢 [입장] 웹소켓 네트워크가 최초 연결되었을 때
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = session.getId();
        sessions.put(sessionId, session);
        // 💡 이제 연결 즉시 DB에 저장하지 않습니다. 로그인에 성공해야 캐릭터가 맵에 나타납니다!
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
                    // 완전히 처음 온 유저라면 기본 좌표(180, 130)와 함께 DB 새 가입 처리
                    Player newPlayer = new Player();
                    newPlayer.setId(username);
                    newPlayer.setPassword(password); // 1단계에서 Player 엔티티에 추가한 필드
                    newPlayer.setX(180);
                    newPlayer.setY(130);
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

                // 계정이 없거나 패스워드가 다를 경우 거절
                if (player == null || !player.getPassword().equals(password)) {
                    loginResult.put("type", "LOGIN_RESULT");
                    loginResult.put("success", false);
                    loginResult.put("message", "아이디 또는 비밀번호가 올바르지 않습니다.");
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(loginResult)));
                    return;
                }

                // 💡 [핵심 인증 체킹] 세션 저장소에 로그인에 성공한 진짜 유저의 아이디를 주입합니다.
                session.getAttributes().put("username", username);
                System.out.println("🔓 [로그인 인증 완료] 트레이너 맵 진입 -> ID: " + username);

                // 💡 [DB 연동] 현재 H2 DB에 등록된 모든 플레이어 목록 수집 (기존 로직 100% 사수)
                Map<String, Map<String, Integer>> currentPlayers = new HashMap<>();
                for (WebSocketSession s : sessions.values()) {
                    String activeUser = (String) s.getAttributes().get("username");

                    if (activeUser != null) {
                        playerRepository.findById(activeUser).ifPresent(p -> {
                            Map<String, Integer> pos = new HashMap<>();
                            pos.put("x", p.getX());
                            pos.put("y", p.getY());
                            currentPlayers.put(p.getId(), pos);
                        });
                    }
                }

                // HTML이 받던 "INIT" 데이터 구조 완벽 연동
                Map<String, Object> initData = new HashMap<>();
                initData.put("type", "INIT");
                initData.put("myId", username); // 이제 내 식별키는 고유 닉네임(아이디)이 됩니다!
                initData.put("players", currentPlayers);
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(initData)));

                // 내가 들어왔음을 다른 유저들에게도 방송 알림
                sendBroadcastUpdate(username, player.getX(), player.getY());
            }

            // 🏃‍♂️ [C] 캐릭터 이동 요청 (기존 이동 로직 완벽 유지)
            else if ("MOVE".equals(type)) {
                // 세션에 저장해뒀던 진짜 로그인 아이디를 꺼냅니다.
                String username = (String) session.getAttributes().get("username");
                // 로그인을 안 하고 해킹 패킷을 보내는 경우 원천 차단
                if (username == null) return;

                int nextX = ((Number) requestData.get("x")).intValue();
                int nextY = ((Number) requestData.get("y")).intValue();

                // 캐릭터 정중앙 기준 타일 충돌 판정 로직 그대로 유지
                int tileX = (nextX + 32) / TILE_SIZE;
                int tileY = (nextY + 32) / TILE_SIZE;

                boolean canMove = true;

                // 맵 경계선 및 벽 충돌 검사 그대로 유지
                if (tileX < 0 || tileX >= 5 || tileY < 0 || tileY >= 5) {
                    canMove = false;
                    System.out.println("🚫 [판정] 맵 밖으로 나가서 이동 불가! (TileX: " + tileX + ", TileY: " + tileY + ")");
                } else if (gameMap[tileY][tileX] == 1) {
                    canMove = false;
                    System.out.println("🚫 [판정] 벽에 부딪혀서 이동 불가! (TileX: " + tileX + ", TileY: " + tileY + ")");
                }

                // 💡 이동이 가능할 때만 H2 DB의 좌표를 업데이트합니다.
                if (canMove) {
                    System.out.println("✅ [판정] 이동 가능! 유저 [" + username + "] -> X: " + nextX + ", Y: " + nextY);
                    playerRepository.findById(username).ifPresent(p -> {
                        p.setX(nextX);
                        p.setY(nextY);
                        playerRepository.save(p);
                    });
                }

                // 💡 DB에서 최종 확정된 내 좌표를 다시 꺼내옵니다. (거절당했다면 이전 좌표가 나옴)
                Player currentState = playerRepository.findById(username).orElse(null);
                if (currentState != null) {
                    sendBroadcastUpdate(username, currentState.getX(), currentState.getY());
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

        // 세션에 숨겨두었던 진짜 로그인 유저 아이디를 꺼냅니다.
        String username = (String) session.getAttributes().get("username");
        System.out.println("❌ [퇴장] 네트워크 소켓 종료 (세션 ID: " + sessionId + " | 로그인 유저: " + username + ")");

        if (username != null) {
            // HTML이 받던 "REMOVE" 브로드캐스트 로직 그대로 유지
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

    // 📡 [공통 방송용 헬퍼 메서드] 중복을 방지하기 위해 밖으로 추출
    private void sendBroadcastUpdate(String username, int x, int y) throws Exception {
        Map<String, Object> responseData = new HashMap<>();
        responseData.put("type", "UPDATE");
        responseData.put("id", username);
        responseData.put("x", x);
        responseData.put("y", y);

        String jsonResponse = objectMapper.writeValueAsString(responseData);
        for (WebSocketSession s : sessions.values()) {
            if (s.isOpen()) {
                s.sendMessage(new TextMessage(jsonResponse));
            }
        }
    }
}