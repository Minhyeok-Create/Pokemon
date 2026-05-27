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

                    // 🔍 1. 현재 세션에 등록된 출전 포켓몬 ID와 가방 속 진짜 포켓몬 매칭하기
                    CapturedPokemon myActivePoke = null;
                    if (battle.getPlayerActivePokemonUniqueId() != null) {
                        for (CapturedPokemon poke : myPokes) {
                            if (poke.getUniqueId().equals(battle.getPlayerActivePokemonUniqueId())) {
                                myActivePoke = poke;
                                break;
                            }
                        }
                    }

                    // 🔍 2. 만약 첫 턴이라서 출전 기록이 없다면? 가방의 0번째 녀석을 선발 투수로 자동 등록!
                    if (myActivePoke == null) {
                        myActivePoke = myPokes.get(0);
                        battle.setPlayerActivePokemonUniqueId(myActivePoke.getUniqueId());
                    }

                    String activePokeName = myActivePoke.getPokemonName().trim();

                    // 🛑 기절한 상태라면 공격 불가능 가드
                    if (myActivePoke.getCurrentHp() <= 0) {
                        res.put("log", "❌ " + activePokeName + "은(는) 기절하여 싸울 수 없습니다! 오른쪽 아래 대기실에서 교체할 포켓몬을 고르세요.");
                        res.put("battleEnded", false);
                        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(res)));
                        return;
                    }

                    // ⚔️ 아군 선제 공격 계산
                    int dmg = random.nextInt(5) + 4;
                    battle.setEnemyCurrentHp(Math.max(0, battle.getEnemyCurrentHp() - dmg));
                    res.put("enemyHp", battle.getEnemyCurrentHp());

                    // 🔥 [중요]: 공격을 수행한 포켓몬의 이름을 명시하여 프론트엔드가 엉뚱한 스킨으로 롤백하는 걸 원천 차단!
                    res.put("myPokemonName", activePokeName);

                    // 🏆 야생 포켓몬이 쓰러진 경우 (전투 승리)
                    if (battle.getEnemyCurrentHp() <= 0) {
                        res.put("log", "💥 " + activePokeName + "의 몸통박치기 공격! (" + dmg + " 데미지!)\n\n🎉 야생의 " + battle.getEnemyName() + "이(가) 쓰러졌다! 승리했습니다!");
                        res.put("battleEnded", true);
                        activeBattles.remove(username);
                    }
                    // 🔄 야생 포켓몬이 살아남아 반격하는 경우
                    else {
                        int counterDmg = random.nextInt(3) + 1;
                        int nextMyHp = Math.max(0, myActivePoke.getCurrentHp() - counterDmg);

                        myActivePoke.setCurrentHp(nextMyHp);
                        capturedPokemonRepository.saveAndFlush(myActivePoke);

                        res.put("myPokemonHp", nextMyHp);
                        res.put("myPokemonMaxHp", myActivePoke.getMaxHp());

                        // 💀 반격을 맞고 내 포켓몬이 기절한 경우
                        if (nextMyHp <= 0) {
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
                                // 🏥 보유 포켓몬이 전멸한 경우 -> 포켓몬센터 강제 이송
                                res.put("log", "💥 " + activePokeName + "의 공격! (" + dmg + " 데미지!)\n🏃‍♂️ 야생의 " + battle.getEnemyName() + "의 반격! (" + counterDmg + " 피해!)\n\n💀 모든 포켓몬이 기절했습니다!\n🏥 눈앞이 깜깜해졌다! 당신은 급히 마을 포켓몬센터로 이송되었습니다!");
                                res.put("battleEnded", true);
                                activeBattles.remove(username);

                                authService.updatePlayerPosition(username, 240, 160, "town");
                                for (CapturedPokemon poke : myPokes) {
                                    poke.setCurrentHp(poke.getMaxHp());
                                    capturedPokemonRepository.saveAndFlush(poke);
                                }
                                sendBroadcastUpdate(username, 240, 160, "town");
                            }
                        } else {
                            // 🏃 정상적인 교전 지속 로그
                            String[] skills = {"전광석화", "몸통박치기", "할퀴기"};
                            res.put("log", "💥 " + activePokeName + "의 " + skills[random.nextInt(skills.length)] + "! (" + dmg + " 데미지!)\n🏃‍♂️ 야생의 " + battle.getEnemyName() + "의 반격! (" + counterDmg + " 피해!)");
                            res.put("battleEnded", false);
                        }
                    }
                }
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(res)));
            }

            // 🔄 [J] 전투 커맨드: 출전 포켓몬 실시간 스위칭 (🚨 BattleState 고유 ID 마킹 교체 방식)
            else if ("BATTLE_SWITCH".equals(type)) {
                if (username == null || !activeBattles.containsKey(username)) return;
                BattleState battle = activeBattles.get(username);

                long targetUniqueId = ((Number) requestData.get("pokemonId")).longValue();
                java.util.List<CapturedPokemon> myPokes = capturedPokemonRepository.findByOwnerId(username);

                if (!myPokes.isEmpty()) {
                    CapturedPokemon targetPoke = null;
                    for (CapturedPokemon poke : myPokes) {
                        if (poke.getUniqueId() == targetUniqueId) {
                            targetPoke = poke;
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

                    // 🔍 현재 필드에 소환되어 있던 포켓몬 수색하기
                    CapturedPokemon currentActivePoke = null;
                    if (battle.getPlayerActivePokemonUniqueId() != null) {
                        for (CapturedPokemon poke : myPokes) {
                            if (poke.getUniqueId().equals(battle.getPlayerActivePokemonUniqueId())) {
                                currentActivePoke = poke;
                                break;
                            }
                        }
                    }
                    if (currentActivePoke == null) currentActivePoke = myPokes.get(0);

                    if (targetPoke != null && !targetPoke.getUniqueId().equals(currentActivePoke.getUniqueId())) {
                        String oldName = currentActivePoke.getPokemonName().trim();
                        String newName = targetPoke.getPokemonName().trim();

                        // 🚨 [가장 중요]: 교체가 완벽히 성공했으므로 BattleState 세션에 새 출전몬 고유 ID 낙인 찍기!
                        battle.setPlayerActivePokemonUniqueId(targetPoke.getUniqueId());

                        if (currentActivePoke.getCurrentHp() <= 0) {
                            res.put("myPokemonName", newName);
                            res.put("myPokemonHp", targetPoke.getCurrentHp());
                            res.put("myPokemonMaxHp", targetPoke.getMaxHp());
                            res.put("log", "💀 기절한 " + oldName + " 대신...\n✨ 가라, " + newName + "!!! 전장으로 안전 투입!");
                            res.put("battleEnded", false);
                        } else {
                            // 선발이 살아있는 상태에서 교체하면 야생몬이 등판한 포켓몬을 기습 타격!
                            int counterDmg = random.nextInt(3) + 1;
                            int finalNewHp = Math.max(0, targetPoke.getCurrentHp() - counterDmg);
                            targetPoke.setCurrentHp(finalNewHp);
                            capturedPokemonRepository.saveAndFlush(targetPoke);

                            res.put("myPokemonName", newName);
                            res.put("myPokemonHp", finalNewHp);
                            res.put("myPokemonMaxHp", targetPoke.getMaxHp());

                            if (finalNewHp <= 0) {
                                res.put("log", "🔄 " + oldName + " 대신 " + newName + "이(가) 등판했으나 기습 공격! (" + counterDmg + " 피해!)\n💀 앗! " + newName + "이(가) 나오자마자 기절해 버렸습니다!");
                                res.put("battleEnded", false);
                            } else {
                                res.put("log", "🔄 기습을 견뎌내고 " + oldName + " 복귀!\n✨ 가라, " + newName + "!!! 등판 완료!");
                                res.put("battleEnded", false);
                            }
                        }
                    } else {
                        res.put("log", "❌ 이미 전장에 출전 중인 포켓몬입니다!");
                        res.put("battleEnded", false);
                    }

                    // 가방 정렬 순서 안 맞추고 원본 그대로 안전 사출 (어차피 프론트는 이름 기반 매핑이라 안 꼬임)
                    Map<String, Object> bagResponse = new HashMap<>();
                    bagResponse.put("type", "BAG_RESULT");
                    bagResponse.put("list", myPokes);
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(bagResponse)));

                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(res)));
                }
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
                        String newName = targetPoke.getPokemonName().trim();

                        // 💡 [핵심 연산]: DB 삭제/생성을 하지 않고, 리스트 상에서 위치(Swap)만 칼같이 바꾼 뒤 저장합니다!
                        myPokes.set(0, targetPoke);
                        myPokes.set(targetIndex, currentActivePoke);

                        if (currentActivePoke.getCurrentHp() <= 0) {
                            // 선발이 이미 기절 상태였다면 기습 없이 프리패스 교체
                            res.put("myPokemonName", newName); // 🔥 프론트엔드가 즉시 스킨을 바꿀 수 있게 명시!
                            res.put("myPokemonHp", targetPoke.getCurrentHp());
                            res.put("myPokemonMaxHp", targetPoke.getMaxHp());
                            res.put("log", "💀 기절한 " + oldName + " 대신...\n✨ 가라, " + newName + "!!! 전장으로 안전 투입!");
                            res.put("battleEnded", false);
                        } else {
                            // 살아있는 상태의 교체는 야생몬 기습 타격 가동 (교체되어 새로 나온 포켓몬이 맞음)
                            int counterDmg = random.nextInt(3) + 1;
                            int finalNewHp = Math.max(0, targetPoke.getCurrentHp() - counterDmg);
                            targetPoke.setCurrentHp(finalNewHp);
                            capturedPokemonRepository.saveAndFlush(targetPoke);

                            res.put("myPokemonName", newName); // 🔥 기습을 맞더라도 나간 녀석은 새로운 녀석임!
                            res.put("myPokemonHp", finalNewHp);
                            res.put("myPokemonMaxHp", targetPoke.getMaxHp());

                            if (finalNewHp <= 0) {
                                res.put("log", "🔄 " + oldName + " 대신 " + newName + "이(가) 등판했으나 기습 공격! (" + counterDmg + " 피해!)\n💀 앗! " + newName + "이(가) 나오자마자 기절해 버렸습니다! 다시 다른 포켓몬을 선택해 주세요.");
                                res.put("battleEnded", false);
                            } else {
                                res.put("log", "🔄 기습을 견뎌내고 " + oldName + " 복귀!\n✨ 가라, " + newName + "!!! 등판 완료!");
                                res.put("battleEnded", false);
                            }
                        }
                    } else {
                        res.put("log", "❌ 이미 전장에 출전 중인 포켓몬입니다!");
                        res.put("battleEnded", false);
                    }

                    // 🚨 [동기화 꿀팁]: 순서가 완벽히 스왑된 가방 데이터(BAG_RESULT) 패킷을 '전투 결과보다 먼저' 사출하여 프론트의 메모리를 선행 세팅합니다!
                    Map<String, Object> bagResponse = new HashMap<>();
                    bagResponse.put("type", "BAG_RESULT");
                    bagResponse.put("list", myPokes);
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(bagResponse)));

                    // 그 후 배틀 라운드 결과를 사출합니다.
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(res)));
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