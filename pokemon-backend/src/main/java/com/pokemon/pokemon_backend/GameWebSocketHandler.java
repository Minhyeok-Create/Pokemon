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

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 💡 OOP 원칙에 맞게 역할이 분리된 전담 서비스 객체들을 주입받습니다.
    @Autowired
    private AuthService authService;

    @Autowired
    private MapService mapService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.put(session.getId(), session);
        System.out.println("⚡ [네트워크 연결] 세션 ID: " + session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        System.out.println("\n📥 [수신] -> " + payload);

        try {
            Map<String, Object> requestData = objectMapper.readValue(payload, Map.class);
            String type = (String) requestData.get("type");
            if (type == null) return;

            // 📝 [A] 회원가입 처리 (AuthService 위임)
            if ("SIGNUP".equals(type)) {
                String username = (String) requestData.get("username");
                String password = (String) requestData.get("password");

                Map<String, Object> result = new HashMap<>();
                result.put("type", "SIGNUP_RESULT");

                if (authService.isUsernameTaken(username)) {
                    result.put("success", false);
                    result.put("message", "이미 사용 중인 아이디입니다.");
                } else {
                    authService.registerNewPlayer(username, password);
                    result.put("success", true);
                    result.put("message", "트레이너 등록이 완료되었습니다!");
                }
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(result)));
            }

            // 🔑 [B] 로그인 처리 (AuthService 위임)
            else if ("LOGIN".equals(type)) {
                String username = (String) requestData.get("username");
                String password = (String) requestData.get("password");

                Player player = authService.loadPlayer(username);
                if (player == null || !player.getPassword().equals(password)) {
                    Map<String, Object> failResult = new HashMap<>();
                    failResult.put("type", "LOGIN_RESULT");
                    failResult.put("success", false);
                    failResult.put("message", "아이디 또는 비밀번호가 올바르지 않습니다.");
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(failResult)));
                    return;
                }

                session.getAttributes().put("username", username);

                // 실시간 접속 유저들의 최신 세션 데이터 매핑
                Map<String, Object> currentPlayers = new HashMap<>();
                for (WebSocketSession s : sessions.values()) {
                    String activeUser = (String) s.getAttributes().get("username");
                    if (activeUser != null) {
                        Player p = authService.loadPlayer(activeUser);
                        if (p != null) {
                            Map<String, Object> pData = new HashMap<>();
                            pData.put("x", p.getX());
                            pData.put("y", p.getY());
                            pData.put("map", p.getMap() != null ? p.getMap().toLowerCase() : "town");
                            currentPlayers.put(p.getId(), pData);
                        }
                    }
                }

                Map<String, Object> initData = new HashMap<>();
                initData.put("type", "INIT");
                initData.put("myId", username);
                initData.put("players", currentPlayers);
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(initData)));

                String initialMap = player.getMap() != null ? player.getMap().toLowerCase() : "town";
                sendBroadcastUpdate(username, player.getX(), player.getY(), initialMap);
            }

            // 🏃‍♂️ [C] 캐릭터 이동 요청 (MapService 물리 연산 검증 -> AuthService 반영)
            else if ("MOVE".equals(type)) {
                String username = (String) session.getAttributes().get("username");
                if (username == null) return;

                int nextX = ((Number) requestData.get("x")).intValue();
                int nextY = ((Number) requestData.get("y")).intValue();
                String clientMap = (String) requestData.get("map");
                String safeClientMap = (clientMap != null) ? clientMap.toLowerCase() : "town";

                Player player = authService.loadPlayer(username);
                if (player != null) {
                    String currentDbMap = player.getMap() != null ? player.getMap().toLowerCase() : "town";

                    boolean canMove = mapService.checkCollision(nextX, nextY, currentDbMap, safeClientMap);

                    if (canMove) {
                        authService.updatePlayerPosition(username, nextX, nextY, safeClientMap);

                        // 🌾 [추가] 이동 성공 시 발밑 타일 조사 후 포켓몬 조우 계산
                        String encounteredPokemon = mapService.checkEncounter(nextX, nextY, safeClientMap);

                        if (encounteredPokemon != null) {
                            System.out.println("🌾 [이벤트 발생] 유저 [" + username + "] 풀숲에서 " + encounteredPokemon + " 마주침!");

                            // 🎲 야생 포켓몬 스펙 랜덤 빌드 (레벨 2~4, HP 12~16 임시 부여)
                            int randomLevel = new java.util.Random().nextInt(3) + 2;
                            int randomHp = 10 + (randomLevel * 2);
                            // 📡 조우한 유저 당사자 소켓에게 전용 특수 통지 전송
                            Map<String, Object> encounterPacket = new HashMap<>();
                            encounterPacket.put("type", "WILD_ENCOUNTER");
                            encounterPacket.put("pokemonName", encounteredPokemon);
                            encounterPacket.put("level", randomLevel);
                            encounterPacket.put("hp", randomHp);
                            encounterPacket.put("maxHp", randomHp);
                            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(encounterPacket)));
                        }
                    }
                }

                Player currentState = authService.loadPlayer(username);
                if (currentState != null) {
                    String savedMap = currentState.getMap() != null ? currentState.getMap().toLowerCase() : "town";
                    sendBroadcastUpdate(username, currentState.getX(), currentState.getY(), savedMap);
                }
            }

            // 💬 [D] 채팅 처리
            else if ("CHAT".equals(type)) {
                String username = (String) session.getAttributes().get("username");
                if (username == null) return;

                Map<String, Object> chatData = new HashMap<>();
                chatData.put("type", "CHAT_MESSAGE");
                chatData.put("id", username);
                chatData.put("msg", requestData.get("msg"));

                String jsonResponse = objectMapper.writeValueAsString(chatData);
                for (WebSocketSession s : sessions.values()) {
                    if (s.isOpen()) s.sendMessage(new TextMessage(jsonResponse));
                }
            }

            else if ("GET_BAG".equals(type)) {
                String username = (String) session.getAttributes().get("username");
                if (username == null) return;

                // DB에서 소지 포켓몬 리스트 수집
                java.util.List<CapturedPokemon> myPokemons = authService.getMyPokemons(username);

                Map<String, Object> bagResponse = new HashMap<>();
                bagResponse.put("type", "BAG_RESULT");
                bagResponse.put("list", myPokemons);

                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(bagResponse)));
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessions.remove(session.getId());
        String username = (String) session.getAttributes().get("username");

        if (username != null) {
            Map<String, Object> removeData = new HashMap<>();
            removeData.put("type", "REMOVE");
            removeData.put("id", username);

            String jsonResponse = objectMapper.writeValueAsString(removeData);
            for (WebSocketSession s : sessions.values()) {
                if (s.isOpen()) s.sendMessage(new TextMessage(jsonResponse));
            }
        }
    }

    private void sendBroadcastUpdate(String username, int x, int y, String mapName) throws Exception {
        Map<String, Object> responseData = new HashMap<>();
        responseData.put("type", "UPDATE");
        responseData.put("id", username);
        responseData.put("x", x);
        responseData.put("y", y);
        responseData.put("map", mapName.toLowerCase());

        String jsonResponse = objectMapper.writeValueAsString(responseData);
        for (WebSocketSession s : sessions.values()) {
            if (s.isOpen()) s.sendMessage(new TextMessage(jsonResponse));
        }
    }
}