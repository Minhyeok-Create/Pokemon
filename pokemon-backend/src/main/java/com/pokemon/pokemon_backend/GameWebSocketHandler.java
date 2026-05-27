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

    // 실시간 전투 세션 관리 메모리 주머니
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

            // 🏃‍♂️ [C] 캐릭터 이동 요청
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

                        if (!isOnCenterTile) {
                            String encounteredPokemon = mapService.checkEncounter(nextX, nextY, safeClientMap);

                            if (encounteredPokemon != null) {
                                System.out.println("🌾 [이벤트 발생] 유저 [" + username + "] 풀숲에서 " + encounteredPokemon + " 마주침!");

                                int randomLevel = random.nextInt(3) + 2;
                                int randomHp = 10 + (randomLevel * 3);

                                activeBattles.put(username, new BattleState(username, encounteredPokemon, randomLevel, randomHp));

                                int myCurrentHp = 20;
                                int myMaxHp = 20;

                                java.util.List<CapturedPokemon> myPokes = capturedPokemonRepository.findByOwnerId(username);
                                if (!myPokes.isEmpty()) {
                                    CapturedPokemon activePoke = myPokes.get(0);
                                    myCurrentHp = activePoke.getCurrentHp();
                                    myMaxHp = activePoke.getMaxHp();
                                }

                                Map<String, Object> encounterPacket = new HashMap<>();
                                encounterPacket.put("type", "WILD_ENCOUNTER");
                                encounterPacket.put("pokemonName", encounteredPokemon);
                                encounterPacket.put("level", randomLevel);
                                encounterPacket.put("hp", randomHp);
                                encounterPacket.put("maxHp", randomHp);
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

            // 🥊 [D] 전투 커맨드: 공격하기
            else if ("BATTLE_ATTACK".equals(type)) {
                if (username == null || !activeBattles.containsKey(username)) return;
                BattleState battle = activeBattles.get(username);

                Map<String, Object> res = new HashMap<>();
                res.put("type", "BATTLE_ROUND_RESULT");

                java.util.List<CapturedPokemon> myPokes = capturedPokemonRepository.findByOwnerId(username);
                if (!myPokes.isEmpty()) {
                    CapturedPokemon myActivePoke = myPokes.get(0);
                    String activePokeName = myActivePoke.getPokemonName().trim();

                    if (myActivePoke.getCurrentHp() <= 0) {
                        res.put("log", "❌ " + activePokeName + "은(는) 기절하여 싸울 수 없습니다! 오른쪽 아래에서 교체할 포켓몬을 고르세요.");
                        res.put("battleEnded", false);
                        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(res)));
                        return;
                    }

                    int dmg = random.nextInt(5) + 4;
                    battle.setEnemyCurrentHp(Math.max(0, battle.getEnemyCurrentHp() - dmg));
                    res.put("enemyHp", battle.getEnemyCurrentHp());

                    if (battle.getEnemyCurrentHp() <= 0) {
                        res.put("log", "💥 " + activePokeName + "의 몸통박치기 공격! (" + dmg + " 데미지!)\n\n🎉 야생의 " + battle.getEnemyName() + "이(가) 쓰러졌다! 승리했습니다!");
                        res.put("battleEnded", true);
                        activeBattles.remove(username);
                    }
                    else {
                        int counterDmg = random.nextInt(3) + 1;
                        int nextMyHp = Math.max(0, myActivePoke.getCurrentHp() - counterDmg);

                        myActivePoke.setCurrentHp(nextMyHp);
                        capturedPokemonRepository.saveAndFlush(myActivePoke);

                        res.put("myPokemonHp", nextMyHp);
                        res.put("myPokemonMaxHp", myActivePoke.getMaxHp());

                        if (nextMyHp <= 0) {
                            // 가방 속 모든 포켓몬 생사 전수 조사
                            boolean hasAlivePokemon = false;
                            for (CapturedPokemon poke : myPokes) {
                                if (poke.getCurrentHp() > 0) {
                                    hasAlivePokemon = true;
                                    break;
                                }
                            }

                            if (hasAlivePokemon) {
                                res.put("log", "💥 " + activePokeName + "의 공격! (" + dmg + " 데미지!)\n🏃‍♂️ 야생의 " + battle.getEnemyName() + "의 반격! (" + counterDmg + " 피해!)\n\n💀 " + activePokeName + "이(가) 기절했습니다... 교체할 다른 포켓몬을 고르세요!");
                                res.put("battleEnded", false);
                            } else {
                                // 💀 [전멸 발생 및 응급 이송 시퀀스 가동]
                                res.put("log", "💥 " + activePokeName + "의 공격! (" + dmg + " 데미지!)\n🏃‍♂️ 야생의 " + battle.getEnemyName() + "의 반격! (" + counterDmg + " 피해!)\n\n💀 앗!... 모든 포켓몬이 기절했습니다!\n🏥 눈앞이 깜깜해졌다! 당신은 급히 마을 포켓몬센터로 이송되었습니다! (소지 포켓몬 전원 완치)");
                                res.put("battleEnded", true);
                                activeBattles.remove(username); // 전투 세션 폐기

                                // 1. 텔레포트: 유저의 위치를 'town' 맵의 포켓몬센터 치료 타일(X=240, Y=160)로 강제 조정 및 DB 커밋
                                authService.updatePlayerPosition(username, 240, 160, "town");

                                // 2. 자동 치료: 이송된 기념으로 소지한 모든 포켓몬의 체력을 100% 풀피로 원격 완치 처리
                                for (CapturedPokemon poke : myPokes) {
                                    poke.setCurrentHp(poke.getMaxHp());
                                    capturedPokemonRepository.saveAndFlush(poke);
                                }

                                // 3. 실시간 가방 UI 리스트 동기화 패킷 사출
                                Map<String, Object> bagResponse = new HashMap<>();
                                bagResponse.put("type", "BAG_RESULT");
                                bagResponse.put("list", myPokes);
                                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(bagResponse)));

                                // 4. 실시간 전체 맵 브로드캐스팅 사출 (다른 유저들 화면에도 내 캐릭터가 센터로 순간이동한 것을 보여줌)
                                sendBroadcastUpdate(username, 240, 160, "town");
                            }
                        } else {
                            String[] skills = {"전광석화", "몸통박치기", "할퀴기"};
                            String randomSkill = skills[random.nextInt(skills.length)];
                            res.put("log", "💥 " + activePokeName + "의 " + randomSkill + "! (" + dmg + " 데미지!)\n🏃‍♂️ 야생의 " + battle.getEnemyName() + "의 반격! (" + counterDmg + " 피해!)");
                            res.put("battleEnded", false);
                        }
                    }
                } else {
                    res.put("log", "❌ 전투할 포켓몬이 가방에 없습니다.");
                    res.put("battleEnded", true);
                    activeBattles.remove(username);
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
                    capturedPokemonRepository.saveAndFlush(cp);

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

            // 🔄 [J] 전투 커맨드: 출전 포켓몬 실시간 스위칭 (🚨 기절 교체 완벽 수리 완료 버전)
            else if ("BATTLE_SWITCH".equals(type)) {
                if (username == null || !activeBattles.containsKey(username)) return;
                BattleState battle = activeBattles.get(username);

                long targetUniqueId = ((Number) requestData.get("pokemonId")).longValue();
                java.util.List<CapturedPokemon> myPokes = capturedPokemonRepository.findByOwnerId(username);

                if (!myPokes.isEmpty()) {
                    CapturedPokemon targetPoke = null;
                    int targetIndex = -1;

                    for (int i = 0; i < myPokes.size(); i++) {
                        if (myPokes.get(i).getUniqueId() == targetUniqueId) {
                            targetPoke = myPokes.get(i);
                            targetIndex = i;
                            break;
                        }
                    }

                    Map<String, Object> res = new HashMap<>();
                    res.put("type", "BATTLE_ROUND_RESULT");
                    res.put("enemyHp", battle.getEnemyCurrentHp());

                    if (targetPoke != null && targetPoke.getCurrentHp() <= 0) {
                        res.put("log", "❌ [경고] 기절하여 의식을 잃은 " + targetPoke.getPokemonName() + "은(는) 필드에 나설 수 없습니다!");
                        res.put("battleEnded", false);
                        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(res)));
                        return;
                    }

                    if (targetPoke != null && targetIndex > 0) {
                        CapturedPokemon currentActivePoke = myPokes.get(0);
                        String oldName = currentActivePoke.getPokemonName().trim();

                        // ✨ [핵심 해결 기믹]: 현재 최전선 0번 포켓몬이 이미 기절해 있는지 여부에 따라 분기 처리!
                        if (currentActivePoke.getCurrentHp() <= 0) {
                            // 💡 이미 기절해 있다면 선제 피격 공격 단계를 완벽히 생략하고 즉시 안전 하이패스 스와프 진행!
                            capturedPokemonRepository.delete(currentActivePoke);
                            capturedPokemonRepository.delete(targetPoke);
                            capturedPokemonRepository.flush();

                            CapturedPokemon newFirst = new CapturedPokemon();
                            newFirst.setOwnerId(username);
                            newFirst.setPokemonName(targetPoke.getPokemonName());
                            newFirst.setLevel(targetPoke.getLevel());
                            newFirst.setMaxHp(targetPoke.getMaxHp());
                            newFirst.setCurrentHp(targetPoke.getCurrentHp());
                            capturedPokemonRepository.saveAndFlush(newFirst);

                            CapturedPokemon newSecond = new CapturedPokemon();
                            newSecond.setOwnerId(username);
                            newSecond.setPokemonName(currentActivePoke.getPokemonName());
                            newSecond.setLevel(currentActivePoke.getLevel());
                            newSecond.setMaxHp(currentActivePoke.getMaxHp());
                            newSecond.setCurrentHp(currentActivePoke.getCurrentHp());
                            capturedPokemonRepository.saveAndFlush(newSecond);

                            myPokes = capturedPokemonRepository.findByOwnerId(username);

                            res.put("myPokemonHp", newFirst.getCurrentHp());
                            res.put("myPokemonMaxHp", newFirst.getMaxHp());
                            res.put("log", "💀 기절한 " + oldName + " 대신...\n✨ 가라, " + newFirst.getPokemonName() + "!!! 전장으로 구원 투입!");
                            res.put("battleEnded", false);

                        } else {
                            // 💡 살아있는 상태에서의 일반 전략적 교체는 기존처럼 기습 피격 계산 실행
                            int counterDmg = random.nextInt(3) + 1;
                            int finalOldHp = Math.max(0, currentActivePoke.getCurrentHp() - counterDmg);
                            currentActivePoke.setCurrentHp(finalOldHp);
                            capturedPokemonRepository.saveAndFlush(currentActivePoke);

                            if (finalOldHp <= 0) {
                                res.put("log", "🔄 " + oldName + "을(를) 불러들이려 했으나 기습 공격! (" + counterDmg + " 피해!)\n💀 앗! " + oldName + "이(가) 들어가기 전에 기절해 버렸습니다! 다시 다른 포켓몬을 선택해 주세요.");
                                res.put("myPokemonHp", 0);
                                res.put("myPokemonMaxHp", currentActivePoke.getMaxHp());
                                res.put("battleEnded", false);
                            } else {
                                capturedPokemonRepository.delete(currentActivePoke);
                                capturedPokemonRepository.delete(targetPoke);
                                capturedPokemonRepository.flush();

                                CapturedPokemon newFirst = new CapturedPokemon();
                                newFirst.setOwnerId(username);
                                newFirst.setPokemonName(targetPoke.getPokemonName());
                                newFirst.setLevel(targetPoke.getLevel());
                                newFirst.setMaxHp(targetPoke.getMaxHp());
                                newFirst.setCurrentHp(targetPoke.getCurrentHp());
                                capturedPokemonRepository.saveAndFlush(newFirst);

                                CapturedPokemon newSecond = new CapturedPokemon();
                                newSecond.setOwnerId(username);
                                newSecond.setPokemonName(currentActivePoke.getPokemonName());
                                newSecond.setLevel(currentActivePoke.getLevel());
                                newSecond.setMaxHp(currentActivePoke.getMaxHp());
                                newSecond.setCurrentHp(currentActivePoke.getCurrentHp());
                                capturedPokemonRepository.saveAndFlush(newSecond);

                                myPokes = capturedPokemonRepository.findByOwnerId(username);

                                res.put("myPokemonHp", newFirst.getCurrentHp());
                                res.put("myPokemonMaxHp", newFirst.getMaxHp());
                                res.put("log", "🔄 기습을 견뎌내고 " + oldName + " 복귀!\n✨ 가라, " + newFirst.getPokemonName() + "!!! 등판 완료!");
                                res.put("battleEnded", false);
                            }
                        }
                    } else {
                        res.put("log", "❌ 이미 전장에 출전 중인 포켓몬입니다!");
                        res.put("battleEnded", false);
                    }

                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(res)));

                    Map<String, Object> bagResponse = new HashMap<>();
                    bagResponse.put("type", "BAG_RESULT");
                    bagResponse.put("list", myPokes);
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(bagResponse)));
                }
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

                java.util.List<CapturedPokemon> myPokemons = capturedPokemonRepository.findByOwnerId(username);

                Map<String, Object> bagResponse = new HashMap<>();
                bagResponse.put("type", "BAG_RESULT");
                bagResponse.put("list", myPokemons);

                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(bagResponse)));
            }

            // 🏥 [I] 포켓몬센터 회복 제어
            else if ("HEAL_ALL".equals(type)) {
                if (username == null) return;

                java.util.List<CapturedPokemon> myPokemons = capturedPokemonRepository.findByOwnerId(username);

                if (!myPokemons.isEmpty()) {
                    for (CapturedPokemon poke : myPokemons) {
                        poke.setCurrentHp(poke.getMaxHp());
                        capturedPokemonRepository.saveAndFlush(poke);
                    }

                    activeBattles.remove(username);

                    Map<String, Object> healLog = new HashMap<>();
                    healLog.put("type", "CENTER_HEAL_RESULT");
                    healLog.put("log", "🏥 딩~ 디디~ 딩~ 딩~ ♪ 전원 완벽하게 풀 HP로 회복되었습니다!");
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(healLog)));

                    Map<String, Object> bagResponse = new HashMap<>();
                    bagResponse.put("type", "BAG_RESULT");
                    bagResponse.put("list", myPokemons);
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(bagResponse)));

                    System.out.println("🏥 유저 [" + username + "] 청정 힐링 패킷 사출 완료!");
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