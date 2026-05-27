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

    private final Map<String, BattleState> activeBattles = new ConcurrentHashMap<>();

    @Autowired
    private AuthService authService;

    @Autowired
    private MapService mapService;

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

            // 📝 [A] 회원가입 처리
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

            // 🔑 [B] 로그인 처리
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

            // 🏃‍♂️ [C] 캐릭터 이동 요청 (포켓몬센터 조우 면제 하드 타겟팅 소탕 완료)
            else if ("MOVE".equals(type)) {
                if (username == null) return;
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

                        // 🏥 포켓몬센터 타일(3번) 위에 서 있다면 야생몬 조우를 아예 원천 면제
                        int currentGridC = (int) Math.floor((nextX + 24) / 80);
                        int currentGridR = (int) Math.floor((nextY + 24) / 80);
                        boolean isOnCenterTile = false;

                        if ("town".equals(safeClientMap) && currentGridR >= 0 && currentGridR < 5 && currentGridC >= 0 && currentGridC < 5) {
                            int[][] townMapLayout = {
                                    {0, 0, 0, 1, 1},
                                    {1, 1, 0, 1, 0},
                                    {2, 2, 0, 3, 0},
                                    {2, 1, 1, 1, 0},
                                    {0, 0, 0, 1, 0}
                            };
                            if (townMapLayout[currentGridR][currentGridC] == 3) {
                                isOnCenterTile = true;
                            }
                        }

                        // 센터 타일 밖의 야생 풀숲 무빙일 때만 전투 매칭 가동
                        if (!isOnCenterTile) {
                            String encounteredPokemon = mapService.checkEncounter(nextX, nextY, safeClientMap);

                            if (encounteredPokemon != null) {
                                System.out.println("🌾 [이벤트 발생] 유저 [" + username + "] 풀숲에서 " + encounteredPokemon + " 마주침!");

                                int randomLevel = random.nextInt(3) + 2;
                                int randomHp = 10 + (randomLevel * 3);

                                activeBattles.put(username, new BattleState(username, encounteredPokemon, randomLevel, randomHp));

                                // 🚨 [체력 바 첫 진입 버그 완전 박멸]:
                                // 전투가 열리는 이 찰나의 순간, DB에 저장된 내 출전몬의 찐 체력(포켓몬센터에서 치료 완료된 피 수치)을 직접 수사합니다.
                                int myCurrentHp = 20; // 가방이 비었을 때를 대비한 디폴트 가드 피통 값
                                int myMaxHp = 20;

                                java.util.List<CapturedPokemon> myPokes = capturedPokemonRepository.findByOwnerId(username);
                                if (!myPokes.isEmpty()) {
                                    CapturedPokemon activePoke = myPokes.get(0); // 현재 1선 출전몬
                                    myCurrentHp = activePoke.getCurrentHp();     // 센터에서 치료 완료된 찐 피
                                    myMaxHp = activePoke.getMaxHp();
                                }

                                // 📡 조우 패킷에 내 포켓몬의 실시간 DB 피 상태까지 꽉 채워서 프론트엔드로 사출!
                                Map<String, Object> encounterPacket = new HashMap<>();
                                encounterPacket.put("type", "WILD_ENCOUNTER");
                                encounterPacket.put("pokemonName", encounteredPokemon);
                                encounterPacket.put("level", randomLevel);
                                encounterPacket.put("hp", randomHp);
                                encounterPacket.put("maxHp", randomHp);

                                // 프론트엔드가 첫 조우창을 열 때 찌찌뿌레한 개피가 아닌, 싱싱한 풀피 바를 그리도록 강제 이식
                                encounterPacket.put("myPokemonHp", myCurrentHp);
                                encounterPacket.put("myPokemonMaxHp", myMaxHp);

                                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(encounterPacket)));
                            }
                        }
                    }
                }

                Player currentState = authService.loadPlayer(username);
                if (currentState != null) {
                    String savedMap = currentState.getMap() != null ? currentState.getMap().toLowerCase() : "town";
                    sendBroadcastUpdate(username, currentState.getX(), currentState.getY(), savedMap);
                }
            }

            // 🥊 [D] 전투 커맨드 라우터: 공격하기
            else if ("BATTLE_ATTACK".equals(type)) {
                if (username == null || !activeBattles.containsKey(username)) return;
                BattleState battle = activeBattles.get(username);

                int dmg = random.nextInt(5) + 4;
                battle.setEnemyCurrentHp(Math.max(0, battle.getEnemyCurrentHp() - dmg));

                Map<String, Object> res = new HashMap<>();
                res.put("type", "BATTLE_ROUND_RESULT");
                res.put("enemyHp", battle.getEnemyCurrentHp());

                if (battle.getEnemyCurrentHp() <= 0) {
                    res.put("log", "💥 피카츄의 백만볼트 작렬! (" + dmg + " 데미지!)\n\n🎉 야생의 " + battle.getEnemyName() + "(이)가 쓰러졌다! 전투에서 승리했습니다!");
                    res.put("battleEnded", true);
                    activeBattles.remove(username);
                }
                else {
                    int counterDmg = random.nextInt(3) + 1;

                    java.util.List<CapturedPokemon> myPokes = capturedPokemonRepository.findByOwnerId(username);
                    if (!myPokes.isEmpty()) {
                        CapturedPokemon myActivePoke = myPokes.get(0);
                        int nextMyHp = Math.max(0, myActivePoke.getCurrentHp() - counterDmg);
                        myActivePoke.setCurrentHp(nextMyHp);
                        capturedPokemonRepository.saveAndFlush(myActivePoke); // 확실한 강제 플러시 동기화

                        res.put("myPokemonHp", nextMyHp);
                        res.put("myPokemonMaxHp", myActivePoke.getMaxHp());

                        if (nextMyHp <= 0) {
                            res.put("log", "💥 피카츄의 공격! (" + dmg + " 데미지!)\n🏃‍♂️ 야생의 " + battle.getEnemyName() + "의 반격! (" + counterDmg + " 피해!)\n\n💀 피카츄가 기절했습니다... 전투에서 패배했습니다.");
                            res.put("battleEnded", true);
                            activeBattles.remove(username);

                            myActivePoke.setCurrentHp(1);
                            capturedPokemonRepository.saveAndFlush(myActivePoke);
                        } else {
                            res.put("log", "💥 피카츄의 전광석화 공격! (" + dmg + " 데미지!)\n🏃‍♂️ 야생의 " + battle.getEnemyName() + "의 몸통박치기 반격! (" + counterDmg + " 피해!)");
                            res.put("battleEnded", false);
                        }
                    } else {
                        res.put("log", "💥 피카츄의 공격! (" + dmg + " 데미지!)\n반격 당했지만 출전 포켓몬을 찾을 수 없습니다.");
                        res.put("battleEnded", false);
                    }
                }
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(res)));
            }

            // 🔴 [E] 몬스터볼 포획
            else if ("BATTLE_CATCH".equals(type)) {
                if (username == null || !activeBattles.containsKey(username)) return;
                BattleState battle = activeBattles.get(username);

                double hpRatio = (double) battle.getEnemyCurrentHp() / battle.getEnemyMaxHp();
                double catchChance = 0.30 + (0.50 * (1.0 - hpRatio));

                Map<String, Object> res = new HashMap<>();
                res.put("type", "BATTLE_ROUND_RESULT");
                res.put("enemyHp", battle.getEnemyCurrentHp());

                if (random.nextDouble() < catchChance) {
                    CapturedPokemon cp = new CapturedPokemon();
                    cp.setOwnerId(username);
                    cp.setPokemonName(battle.getEnemyName());
                    cp.setLevel(battle.getEnemyLevel());
                    cp.setMaxHp(battle.getEnemyMaxHp());
                    cp.setCurrentHp(battle.getEnemyMaxHp());
                    capturedPokemonRepository.saveAndFlush(cp); // 즉시 커밋

                    res.put("log", "🔴 몬스터볼을 던졌다! 대굴.. 대굴.. 탁!\n\n🎉 성공! 야생의 " + battle.getEnemyName() + "을(를) 안전하게 붙잡았습니다!");
                    res.put("battleEnded", true);
                    activeBattles.remove(username);
                } else {
                    res.put("log", "🔴 몬스터볼을 던졌다! 아깝다! 포켓몬이 탈출해서 길길이 날뛰고 있습니다!");
                    res.put("battleEnded", false);
                }
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(res)));
            }

            // 🏃‍♂️ [F] 도망치기
            else if ("BATTLE_RUN".equals(type)) {
                if (username == null) return;
                activeBattles.remove(username);

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

            // 🎒 [H] 유저 가방 조회
            else if ("GET_BAG".equals(type)) {
                if (username == null) return;

                java.util.List<CapturedPokemon> myPokemons = authService.getMyPokemons(username);

                Map<String, Object> bagResponse = new HashMap<>();
                bagResponse.put("type", "BAG_RESULT");
                bagResponse.put("list", myPokemons);

                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(bagResponse)));
            }

            // 🏥 [I] 포켓몬센터 완벽 리부트 제어 (DB 플러시 장착)
            else if ("HEAL_ALL".equals(type)) {
                if (username == null) return;

                java.util.List<CapturedPokemon> myPokemons = authService.getMyPokemons(username);

                if (!myPokemons.isEmpty()) {
                    // 1. 가방 속 모든 포켓몬들의 현재 체력을 최대 체력으로 100% 치유 및 DB 반영
                    for (CapturedPokemon poke : myPokemons) {
                        poke.setCurrentHp(poke.getMaxHp());
                        capturedPokemonRepository.saveAndFlush(poke);
                    }

                    // 2. 혹시라도 남아있을 교착된 배틀방 메모리 세션 폐기
                    activeBattles.remove(username);

                    // 3. 🚨 [버그 척결 핵심]: 프론트엔드 배틀 창 타이머를 교란하던 BATTLE_ROUND_RESULT를 영구 퇴출하고
                    // 배틀 타이머 간섭이 0%인 순수 알림 전용 패킷(CENTER_HEAL_RESULT)으로 사출 변경!
                    Map<String, Object> healLog = new HashMap<>();
                    healLog.put("type", "CENTER_HEAL_RESULT");
                    healLog.put("log", "🏥 딩~ 디디~ 딩~ 딩~ ♪ 전원 완벽하게 풀 HP로 회복되었습니다!");
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(healLog)));

                    // 4. 가방 UI 실시간 풀피 동기화 사출
                    Map<String, Object> bagResponse = new HashMap<>();
                    bagResponse.put("type", "BAG_RESULT");
                    bagResponse.put("list", myPokemons);
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(bagResponse)));

                    System.out.println("🏥 [타이머 꼬임 버그 박멸 완수] 유저 [" + username + "] 청정 힐링 패킷 사출 완료!");
                }
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
            activeBattles.remove(username);

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