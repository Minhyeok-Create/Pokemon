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
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class GameWebSocketHandler extends TextWebSocketHandler {

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Random random = new Random();

    // 💡 [메모리 추가] 현재 실시간 1대1 전투가 진행 중인 트레이너 유저들의 배틀 세션 주머니
    private final Map<String, BattleState> activeBattles = new ConcurrentHashMap<>();

    // 💡 OOP 원칙에 맞게 역할이 분리된 전담 서비스 객체들을 주입받습니다.
    @Autowired
    private AuthService authService;

    @Autowired
    private MapService mapService;

    // 💡 몬스터볼 포획 성공 시 가방에 포켓몬을 다이렉트로 집어넣기 위해 주입합니다.
    @Autowired
    private CapturedPokemonRepository capturedPokemonRepository;

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

            String username = (String) session.getAttributes().get("username");

            // 📝 [A] 회원가입 처리 (AuthService 위임)
            if ("SIGNUP".equals(type)) {
                String usernameInput = (String) requestData.get("username");
                String passwordInput = (String) requestData.get("password");

                Map<String, Object> result = new HashMap<>();
                result.put("type", "SIGNUP_RESULT");

                if (authService.isUsernameTaken(usernameInput)) {
                    result.put("success", false);
                    result.put("message", "이미 사용 중인 아이디입니다.");
                } else {
                    authService.registerNewPlayer(usernameInput, passwordInput);
                    result.put("success", true);
                    result.put("message", "트레이너 등록이 완료되었습니다!");
                }
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(result)));
            }

            // 🔑 [B] 로그인 처리 (AuthService 위임)
            else if ("LOGIN".equals(type)) {
                String usernameInput = (String) requestData.get("username");
                String passwordInput = (String) requestData.get("password");

                Player player = authService.loadPlayer(usernameInput);
                if (player == null || !player.getPassword().equals(passwordInput)) {
                    Map<String, Object> failResult = new HashMap<>();
                    failResult.put("type", "LOGIN_RESULT");
                    failResult.put("success", false);
                    failResult.put("message", "아이디 또는 비밀번호가 올바르지 않습니다.");
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(failResult)));
                    return;
                }

                session.getAttributes().put("username", usernameInput);

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
                initData.put("myId", usernameInput);
                initData.put("players", currentPlayers);
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(initData)));

                String initialMap = player.getMap() != null ? player.getMap().toLowerCase() : "town";
                sendBroadcastUpdate(usernameInput, player.getX(), player.getY(), initialMap);
            }

            // 🏃‍♂️ [C] 캐릭터 이동 요청 (MapService 물리 연산 검증 -> AuthService 반영)
            else if ("MOVE".equals(type)) {
                if (username == null) return;

                // 🛑 [전투 중 이동 블록 가드] 전투 중인 상태라면 무빙 관련 연산을 거부하고 탈출시킵니다.
                if (activeBattles.containsKey(username)) return;

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

                            // 🎲 야생 포켓몬 스펙 랜덤 빌드 (레벨 2~4, HP 14~22 랜덤 부여)
                            int randomLevel = random.nextInt(3) + 2;
                            int randomHp = 10 + (randomLevel * 3);

                            // ⚔️ [핵심] 유저 아이디와 야생몬 데이터를 매핑하여 실시간 1:1 전투방 개설
                            activeBattles.put(username, new BattleState(username, encounteredPokemon, randomLevel, randomHp));

                            // 📡 조우한 유저 당사자 소켓에게 전용 특수 배틀 데이터 패킷 전송
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

            // 🥊 [D] 전투 커맨드 라우터: 공격하기 데미지 주사위 연산
            else if ("BATTLE_ATTACK".equals(type)) {
                if (username == null || !activeBattles.containsKey(username)) return;
                BattleState battle = activeBattles.get(username);

                // 1. 내 피카츄의 공격 (4~8 무작위 랜덤 타격)
                int dmg = random.nextInt(5) + 4;
                battle.setEnemyCurrentHp(Math.max(0, battle.getEnemyCurrentHp() - dmg));

                Map<String, Object> res = new HashMap<>();
                res.put("type", "BATTLE_ROUND_RESULT");
                res.put("enemyHp", battle.getEnemyCurrentHp());

                // 2. 야생 포켓몬이 격침되었을 경우
                if (battle.getEnemyCurrentHp() <= 0) {
                    res.put("log", "💥 피카츄의 백만볼트 작렬! (" + dmg + " 데미지!)\n\n🎉 야생의 " + battle.getEnemyName() + "(이)가 쓰러졌다! 전투에서 승리했습니다!");
                    res.put("battleEnded", true);
                    activeBattles.remove(username);
                }
                // 3. 야생 포켓몬이 생존하여 반격을 가할 경우 -> 내 포켓몬 DB 피통 깎기!
                else {
                    int counterDmg = random.nextInt(3) + 1; // 1~3 데미지 반격

                    // DB에서 내 포켓몬 리스트 중 첫 번째(출전몬)를 꺼내와 체력을 깎습니다.
                    java.util.List<CapturedPokemon> myPokes = capturedPokemonRepository.findByOwnerId(username);
                    if (!myPokes.isEmpty()) {
                        CapturedPokemon myActivePoke = myPokes.get(0); // 첫 번째 포켓몬 선택
                        int nextMyHp = Math.max(0, myActivePoke.getCurrentHp() - counterDmg);
                        myActivePoke.setCurrentHp(nextMyHp);
                        capturedPokemonRepository.saveAndFlush(myActivePoke); // DB에 실시간 저장

                        // 프론트엔드가 내 체력 바를 갱신할 수 있게 패킷에 실어 보냅니다.
                        res.put("myPokemonHp", nextMyHp);
                        res.put("myPokemonMaxHp", myActivePoke.getMaxHp());

                        if (nextMyHp <= 0) {
                            res.put("log", "💥 피카츄의 공격! (" + dmg + " 데미지!)\n🏃‍♂️ 야생의 " + battle.getEnemyName() + "의 반격! (" + counterDmg + " 피해!)\n\n💀 피카츄가 기절했습니다... 전투에서 패배했습니다.");
                            res.put("battleEnded", true);
                            activeBattles.remove(username);

                            // 🏥 기절 방지 서비스: 테스트 편의를 위해 치료비 0원 즉시 부활(HP 1로 보정)
                            myActivePoke.setCurrentHp(1);
                            capturedPokemonRepository.saveAndFlush(myActivePoke);
                        } else {
                            res.put("log", "💥 피카츄의 전광석화 공격! (" + dmg + " 데미지!)\n🏃‍♂️ 야생의 " + battle.getEnemyName() + "의 몸통박치기 반격! (" + counterDmg + " 피해!)");
                            res.put("battleEnded", false);
                        }
                    } else {
                        // 혹시 가방에 포켓몬이 없는 예외 상황 방어용
                        res.put("log", "💥 피카츄의 공격! (" + dmg + " 데미지!)\n반격 당했지만 출전 포켓몬을 찾을 수 없습니다.");
                        res.put("battleEnded", false);
                    }
                }
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(res)));
            }

            // 🔴 [E] 전투 커맨드 라우터: 몬스터볼 포획 확률 주사위 및 DB 삽입 연산
            else if ("BATTLE_CATCH".equals(type)) {
                if (username == null || !activeBattles.containsKey(username)) return;
                BattleState battle = activeBattles.get(username);

                // 🎲 포획 연산 공식 기믹: 대상의 잔여 HP 비율이 낮을수록 성공 확률 대폭 보강
                double hpRatio = (double) battle.getEnemyCurrentHp() / battle.getEnemyMaxHp();
                double catchChance = 0.30 + (0.50 * (1.0 - hpRatio)); // 기본 30% ~ 개피 상태일 때 최대 80%

                Map<String, Object> res = new HashMap<>();
                res.put("type", "BATTLE_ROUND_RESULT");
                res.put("enemyHp", battle.getEnemyCurrentHp());

                if (random.nextDouble() < catchChance) {
                    // 🎁 [포획 대성공] 트레이너 가방(DB) 테이블에 새로운 포켓몬 엔티티 영속화!
                    CapturedPokemon cp = new CapturedPokemon();
                    cp.setOwnerId(username);
                    cp.setPokemonName(battle.getEnemyName());
                    cp.setLevel(battle.getEnemyLevel());
                    cp.setMaxHp(battle.getEnemyMaxHp());
                    cp.setCurrentHp(battle.getEnemyMaxHp()); // 잡힌 포켓몬은 풀피 상태로 주입
                    capturedPokemonRepository.save(cp);

                    res.put("log", "🔴 몬스터볼을 던졌다! 대굴.. 대굴.. 탁!\n\n🎉 성공! 야생의 " + battle.getEnemyName() + "을(를) 안전하게 붙잡았습니다!");
                    res.put("battleEnded", true);
                    activeBattles.remove(username); // 전투방 삭제
                } else {
                    res.put("log", "🔴 몬스터볼을 던졌다! 아깝다! 포켓몬이 탈출해서 길길이 날뛰고 있습니다!");
                    res.put("battleEnded", false);
                }
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(res)));
            }

            // 🏃‍♂️ [F] 전투 커맨드 라우터: 도망치기 기믹
            else if ("BATTLE_RUN".equals(type)) {
                if (username == null) return;
                activeBattles.remove(username); // 전투방 정보 제거

                Map<String, Object> res = new HashMap<>();
                res.put("type", "BATTLE_ROUND_RESULT");
                res.put("battleEnded", true);
                res.put("log", "💨 무사히 야생 포켓몬의 시야에서 도망쳤습니다.");
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(res)));
            }

            // 💬 [G] 채팅 처리
            else if ("CHAT".equals(type)) {
                if (username == null) return;

                Map<String, Object> chatData = new HashMap<>();
                chatData.put("type", "CHAT_MESSAGE");
                chatData.put("id", username);
                chatData.put("msg", requestData.get("msg"));

                String jsonResponse = objectMapper.writeValueAsString(chatData);
                for (WebSocketSession s : sessions.values()) {
                    if (s.isOpen()) {
                        s.sendMessage(new TextMessage(jsonResponse));
                    }
                }
            }

            // 🎒 [H] 유저 가방 포켓몬 목록 조회 요청 처리
            else if ("GET_BAG".equals(type)) {
                if (username == null) return;

                // DB에서 해당 주인의 소지 포켓몬 리스트 긁어오기
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
            activeBattles.remove(username); // 로그아웃되거나 튕기면 해당 전투방 잔재 즉시 자동 폐기

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

    private void sendBroadcastUpdate(String username, int x, int y, String mapName) throws Exception {
        Map<String, Object> responseData = new HashMap<>();
        responseData.put("type", "UPDATE");
        responseData.put("id", username);
        responseData.put("x", x);
        responseData.put("y", y);
        responseData.put("map", mapName.toLowerCase());

        String jsonResponse = objectMapper.writeValueAsString(responseData);
        for (WebSocketSession s : sessions.values()) {
            if (s.isOpen()) {
                s.sendMessage(new TextMessage(jsonResponse));
            }
        }
    }
}