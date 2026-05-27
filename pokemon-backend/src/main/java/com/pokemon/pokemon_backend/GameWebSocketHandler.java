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

    // 현재 실시간 1대1 전투가 진행 중인 트레이너 유저들의 배틀 세션 주머니
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

            // 🏃‍♂️ [C] 캐릭터 이동 요청 (검증 완료)
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

            // 🥊 [D] 전투 커맨드 라우터: 공격하기 (🚨 피카츄 고정 버그 & 무한 풀피 부활 버그 소탕 완료)
            else if ("BATTLE_ATTACK".equals(type)) {
                if (username == null || !activeBattles.containsKey(username)) return;
                BattleState battle = activeBattles.get(username);

                Map<String, Object> res = new HashMap<>();
                res.put("type", "BATTLE_ROUND_RESULT");

                // 가방의 0번째 자리에 위치한 진짜 출전 포켓몬 데이터 추적
                java.util.List<CapturedPokemon> myPokes = capturedPokemonRepository.findByOwnerId(username);
                if (!myPokes.isEmpty()) {
                    CapturedPokemon myActivePoke = myPokes.get(0);
                    String activePokeName = myActivePoke.getPokemonName().trim(); // 🛠️ 동적 이름 추적

                    // 1. 아군 출전몬의 공격 연산 (4~8 무작위 타격)
                    int dmg = random.nextInt(5) + 4;
                    battle.setEnemyCurrentHp(Math.max(0, battle.getEnemyCurrentHp() - dmg));
                    res.put("enemyHp", battle.getEnemyCurrentHp());

                    // 2. 적 야생몬 기절 시 승리 처리 및 배틀 해제
                    if (battle.getEnemyCurrentHp() <= 0) {
                        res.put("log", "💥 " + activePokeName + "의 몸통박치기 공격! (" + dmg + " 데미지!)\n\n🎉 야생의 " + battle.getEnemyName() + "(이)가 쓰러졌다! 전투에서 승리했습니다!");
                        res.put("battleEnded", true);
                        activeBattles.remove(username);
                    }
                    // 3. 야생몬 생존 시 반격 연산 개시
                    else {
                        int counterDmg = random.nextInt(3) + 1;
                        int nextMyHp = Math.max(0, myActivePoke.getCurrentHp() - counterDmg);

                        myActivePoke.setCurrentHp(nextMyHp);
                        capturedPokemonRepository.saveAndFlush(myActivePoke); // 실시간 디스크 저장

                        res.put("myPokemonHp", nextMyHp);
                        res.put("myPokemonMaxHp", myActivePoke.getMaxHp());

                        // 💀 4. [버그 척결]: 피가 0이 되면 억지로 강제 부활(HP 1 보정) 시키던 좀비 코드를 삭제하고 정상 기절 패배 처리!
                        if (nextMyHp <= 0) {
                            res.put("log", "💥 " + activePokeName + "의 공격! (" + dmg + " 데미지!)\n🏃‍♂️ 야생의 " + battle.getEnemyName() + "의 반격! (" + counterDmg + " 피해!)\n\n💀 " + activePokeName + "(이)가 기절했습니다... 전투에서 패배했습니다! 포켓몬센터로 가세요.");
                            res.put("battleEnded", true);
                            activeBattles.remove(username); // 교착 없이 전투 세션 해제
                        } else {
                            // 둘 다 생존 시 일반 교전 동적 텍스트 사출
                            String[] skills = {"전광석화", "몸통박치기", "할퀴기", "몸굽히기"};
                            String randomSkill = skills[random.nextInt(skills.length)];
                            res.put("log", "💥 " + activePokeName + "의 " + randomSkill + " 공격! (" + dmg + " 데미지!)\n🏃‍♂️ 야생의 " + battle.getEnemyName() + "의 반격! (" + counterDmg + " 피해!)");
                            res.put("battleEnded", false);
                        }
                    }
                } else {
                    res.put("log", "❌ 전투를 치를 포켓몬이 가방에 없습니다.");
                    res.put("battleEnded", true);
                    activeBattles.remove(username);
                }
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(res)));
            }

            // 🔴 [E] 몬스터볼 포획 (검증 완료)
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

            // 🏃‍♂️ [F] 도망치기 (검증 완료)
            else if ("BATTLE_RUN".equals(type)) {
                if (username == null) return;
                activeBattles.remove(username);

                Map<String, Object> res = new HashMap<>();
                res.put("type", "BATTLE_ROUND_RESULT");
                res.put("battleEnded", true);
                res.put("log", "💨 무사히 야생 포켓몬의 시야에서 도망쳤습니다.");
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(res)));
            }

            // 🔄 [J] 전투 커맨드 라우터: 출전 포켓몬 실시간 스위칭 (검증 완료)
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

                    // 1. 대기실에 있는 교체 대상 포켓몬이 이미 기절해 있다면 출전 불가 처리 (가드)
                    if (targetPoke != null && targetPoke.getCurrentHp() <= 0) {
                        res.put("log", "❌ [경고] 기절하여 의식을 잃은 " + targetPoke.getPokemonName() + "은(는) 필드에 나설 수 없습니다!");
                        res.put("battleEnded", false);
                        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(res)));
                        return;
                    }

                    if (targetPoke != null && targetIndex > 0) {
                        // 🛠️ [정통 룰 교정 핵심]: 현재 최전선에 서 있는 포켓몬(0번 인덱스)을 먼저 소환
                        CapturedPokemon currentActivePoke = myPokes.get(0);
                        String oldName = currentActivePoke.getPokemonName().trim();

                        // 2. 🚨 선(先) 피격: 전장에 있던 기존 포켓몬이 들어가기 직전, 적의 기습 공격을 몸으로 받아냅니다!
                        int counterDmg = random.nextInt(3) + 1;
                        int finalOldHp = Math.max(0, currentActivePoke.getCurrentHp() - counterDmg);
                        currentActivePoke.setCurrentHp(finalOldHp);
                        capturedPokemonRepository.saveAndFlush(currentActivePoke); // DB에 깎인 피 즉시 커밋

                        // 3. 기습을 얻어맞은 포켓몬의 생사 여부에 따른 분기 연출
                        if (finalOldHp <= 0) {
                            // 가방으로 들어가려던 포켓몬이 기습을 맞고 쓰러진 경우 -> 교체 실패 처리 및 다른 몬스터 선택 유도
                            res.put("log", "🔄 " + oldName + "을(를) 불러들이려 했으나, 야생의 " + battle.getEnemyName() + "의 기습 공격! (" + counterDmg + " 피해!)\n💀 앗! " + oldName + "(이)가 가방에 들어가기 전에 기절해 버렸습니다! 다른 포켓몬을 출전시키세요!");
                            res.put("myPokemonHp", 0);
                            res.put("myPokemonMaxHp", currentActivePoke.getMaxHp());
                            res.put("battleEnded", false);

                            // 테스트 편의용 즉시 미세 부활 가드
                            currentActivePoke.setCurrentHp(1);
                            capturedPokemonRepository.saveAndFlush(currentActivePoke);
                        } else {
                            // 버텨냈다면 무사히 바통 터치 스와프(Swap) 알고리즘 단행!
                            myPokes.set(0, targetPoke);
                            myPokes.set(targetIndex, currentActivePoke);

                            // 4. 🚨 [체력바 미갱신 버그 픽스]: 새로 전장에 등판한 포켓몬의 진짜 체력 수치를 패킷에 정밀 주입!
                            res.put("myPokemonHp", targetPoke.getCurrentHp());
                            res.put("myPokemonMaxHp", targetPoke.getMaxHp());

                            res.put("log", "🔄 야생의 " + battle.getEnemyName() + "의 기습을 견뎌내고 " + oldName + " 투입 복귀!\n✨ 가라, " + targetPoke.getPokemonName() + "!!! 전장을 부탁해!");
                            res.put("battleEnded", false);
                        }
                    } else {
                        res.put("log", "❌ 이미 전장에 출전 중인 포켓몬입니다!");
                        res.put("battleEnded", false);
                    }

                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(res)));

                    // 5. 순서와 피통이 요동쳤으니 가방 정보 및 전장 대기실 실시간 전체 동기화 사출
                    Map<String, Object> bagResponse = new HashMap<>();
                    bagResponse.put("type", "BAG_RESULT");
                    bagResponse.put("list", myPokes);
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(bagResponse)));
                }
            }

            // 💬 [G] 채팅 처리 (검증 완료)
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

            // 🎒 [H] 유저 가방 조회 (검증 완료)
            else if ("GET_BAG".equals(type)) {
                if (username == null) return;

                java.util.List<CapturedPokemon> myPokemons = authService.getMyPokemons(username);

                Map<String, Object> bagResponse = new HashMap<>();
                bagResponse.put("type", "BAG_RESULT");
                bagResponse.put("list", myPokemons);

                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(bagResponse)));
            }

            // 🏥 [I] 포켓몬센터 회복 제어 (검증 완료)
            else if ("HEAL_ALL".equals(type)) {
                if (username == null) return;

                java.util.List<CapturedPokemon> myPokemons = authService.getMyPokemons(username);

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