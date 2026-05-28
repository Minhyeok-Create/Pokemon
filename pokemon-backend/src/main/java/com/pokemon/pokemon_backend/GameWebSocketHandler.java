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

    // [PvP 메모리]: 실시간 유저 대인전 세션 및 방 점유 추적용 보관소
    private final Map<String, PvPState> activePvPMatches = new ConcurrentHashMap<>(); // RoomId -> PvPState
    private final Map<String, String> userToRoomMap = new ConcurrentHashMap<>();     // Username -> RoomId

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

            // 🏃‍♂️ [C] 캐릭터 이동 요청 (🚨 미스틱 등급 야생 조우 스펙 격상 연동 완료)
            else if ("MOVE".equals(type)) {
                if (username == null) return;
                if (activeBattles.containsKey(username) || userToRoomMap.containsKey(username)) return;

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

                                int finalLevel;
                                int finalHp;

                                String checkName = encounteredPokemon.trim();
                                if (isLegendaryOrEpic(checkName)) {
                                    finalLevel = random.nextInt(6) + 15; // 초전설 보스: Lv.15 ~ Lv.20
                                    finalHp = 100 + (finalLevel * 2);    // 레이드 피통: HP 130 ~ 140
                                    System.out.println("👑 [초전설/준전설 레이드 보스 강림!] " + checkName + " Lv." + finalLevel + " (HP: " + finalHp + ")");
                                }
                                // ✨ [미스틱 기믹 적용]: 미스틱종 야생 조우 시 에이스급 네임드 스펙 격상 사출
                                else if (isMystic(checkName)) {
                                    finalLevel = random.nextInt(5) + 8;  // 미스틱 에이스: Lv.8 ~ Lv.12
                                    finalHp = 50 + (finalLevel * 3);     // 탄탄한 피통: HP 74 ~ 86
                                    System.out.println("💎 [미스틱 에이스 강림!] " + checkName + " Lv." + finalLevel + " (HP: " + finalHp + ")");
                                } else {
                                    finalLevel = random.nextInt(3) + 2;
                                    finalHp = 10 + (finalLevel * 3);
                                }

                                activeBattles.put(username, new BattleState(username, encounteredPokemon, finalLevel, finalHp));

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
                                encounterPacket.put("level", finalLevel);
                                encounterPacket.put("hp", finalHp);
                                encounterPacket.put("maxHp", finalHp);
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

            // 🥊 [D] 전투 커맨드: 공격하기 (🚨 미스틱 등급 데미지 및 2% 치명타 보정 주입 완료)
            else if ("BATTLE_ATTACK".equals(type)) {
                if (username == null) return;

                if (userToRoomMap.containsKey(username)) {
                    String roomId = userToRoomMap.get(username);
                    PvPState pvp = activePvPMatches.get(roomId);
                    if (pvp != null) {
                        if (username.equals(pvp.getP1Username())) pvp.setP1Action("ATTACK");
                        if (username.equals(pvp.getP2Username())) pvp.setP2Action("ATTACK");

                        checkAndExecutePvPTurn(pvp);
                    }
                    return;
                }

                if (!activeBattles.containsKey(username)) return;
                BattleState battle = activeBattles.get(username);

                Map<String, Object> res = new HashMap<>();
                res.put("type", "BATTLE_ROUND_RESULT");

                java.util.List<CapturedPokemon> myPokes = capturedPokemonRepository.findByOwnerId(username);
                if (!myPokes.isEmpty()) {

                    CapturedPokemon myActivePoke = null;
                    if (battle.getPlayerActivePokemonUniqueId() != null) {
                        for (CapturedPokemon poke : myPokes) {
                            if (poke.getUniqueId().equals(battle.getPlayerActivePokemonUniqueId())) {
                                myActivePoke = poke;
                                break;
                            }
                        }
                    }

                    if (myActivePoke == null) {
                        myActivePoke = myPokes.get(0);
                        battle.setPlayerActivePokemonUniqueId(myActivePoke.getUniqueId());
                    }

                    String activePokeName = myActivePoke.getPokemonName().trim();

                    if (myActivePoke.getCurrentHp() <= 0) {
                        res.put("log", "❌ " + activePokeName + "은(는) 기절하여 싸울 수 없습니다! 오른쪽 아래 대기실에서 교체할 포켓몬을 고르세요.");
                        res.put("battleEnded", false);
                        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(res)));
                        return;
                    }

                    String mySkill = getSkillName(activePokeName);
                    double myMultiplier = getMatchupMultiplier(activePokeName, battle.getEnemyName());

                    // 💥 [내 공격 연산]: 전설(20% 확률 200딜) 및 미스틱(2% 확률 100딜) 주사위 연산 확장
                    int finalMyDmg;
                    int myCritType = 0; // 0: 노크리티컬, 1: 미스틱크리티컬, 2: 전설크리티컬

                    if (isLegendaryOrEpic(activePokeName) && random.nextInt(100) < 20) {
                        finalMyDmg = 200;
                        myCritType = 2;
                    } else if (isMystic(activePokeName) && random.nextInt(100) < 2) {
                        finalMyDmg = 100; // 2% 로또 고정 100 대미지!
                        myCritType = 1;
                    } else {
                        int baseMyDmg = (myActivePoke.getLevel() * 2) + (random.nextInt(5) + 4);
                        // ✨ 미스틱 등급 아군 평타 시 상시 +3 가중치 주입
                        if (isMystic(activePokeName)) {
                            baseMyDmg += 3;
                        }
                        finalMyDmg = (int) Math.round(baseMyDmg * myMultiplier);
                    }

                    battle.setEnemyCurrentHp(Math.max(0, battle.getEnemyCurrentHp() - finalMyDmg));
                    res.put("enemyHp", battle.getEnemyCurrentHp());
                    res.put("myPokemonName", activePokeName);

                    // 🏆 [야생 포켓몬 격파 정산]
                    if (battle.getEnemyCurrentHp() <= 0) {
                        StringBuilder logBuilder = new StringBuilder();
                        if (myCritType == 2) {
                            logBuilder.append("⚡⚡ [💥전설의 궁극기 폭발!!!] 나의 ").append(activePokeName).append("(이)가 신화 속 광선을 뿜어냈습니다! (💥고정 200 데미지!)\n\n");
                        } else if (myCritType == 1) {
                            logBuilder.append("🔥✨ [💎미스틱 에이스 크리티컬!!!] 나의 ").append(activePokeName).append("의 잠재된 진화 에너지가 폭발했습니다! (💥고정 100 데미지!)\n\n");
                        } else {
                            logBuilder.append("💥 ").append(activePokeName).append("의 [").append(mySkill).append("] 공격! (").append(finalMyDmg).append(" 데미지!) ");
                            if (myMultiplier > 1.1) logBuilder.append("✨ 효과가 굉장했다!\n\n");
                            else if (myMultiplier < 0.9) logBuilder.append("💨 효과가 별로인 듯하다...\n\n");
                            else logBuilder.append("\n\n");
                        }

                        logBuilder.append("🎉 야생의 ").append(battle.getEnemyName()).append("이(가) 쓰러졌다!\n");

                        int gainedExp = battle.getEnemyLevel() * 5;
                        myActivePoke.setExp(myActivePoke.getExp() + gainedExp);
                        logBuilder.append("✨ ").append(activePokeName).append("은(는) ").append(gainedExp).append(" XP의 경험치를 획득했다! ");

                        int neededExp = myActivePoke.getLevel() * 20;
                        boolean leveledUp = false;

                        while (myActivePoke.getExp() >= neededExp) {
                            myActivePoke.setExp(myActivePoke.getExp() - neededExp);
                            myActivePoke.setLevel(myActivePoke.getLevel() + 1);
                            myActivePoke.setMaxHp(myActivePoke.getMaxHp() + 3);
                            myActivePoke.setCurrentHp(myActivePoke.getMaxHp());

                            leveledUp = true;
                            neededExp = myActivePoke.getLevel() * 20;
                        }

                        if (leveledUp) {
                            logBuilder.append("\n🆙 ★★★ 축하합니다! ").append(activePokeName)
                                    .append("(이)가 Lv.").append(myActivePoke.getLevel())
                                    .append("(으)로 레벨업했습니다! (최대 HP +3) ★★★");
                        } else {
                            logBuilder.append("(").append(myActivePoke.getExp()).append("/").append(neededExp).append(" XP)");
                        }

                        capturedPokemonRepository.saveAndFlush(myActivePoke);

                        res.put("log", logBuilder.toString());
                        res.put("battleEnded", true);
                        activeBattles.remove(username);

                        myPokes = capturedPokemonRepository.findByOwnerId(username);
                        Map<String, Object> bagResponse = new HashMap<>();
                        bagResponse.put("type", "BAG_RESULT");
                        bagResponse.put("list", myPokes);
                        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(bagResponse)));
                    }
                    // 🔄 야생 포켓몬 반격 타임 (🚨 적 미스틱 2% 확률 100 고정 필살기 적용)
                    else {
                        String enemySkill = getSkillName(battle.getEnemyName());
                        double enemyMultiplier = getMatchupMultiplier(battle.getEnemyName(), activePokeName);

                        int finalEnemyDmg;
                        int enemyCritType = 0; // 0: 노크리티컬, 1: 미스틱크리티컬, 2: 전설크리티컬
                        String checkEnemy = battle.getEnemyName().trim();

                        if (isLegendaryOrEpic(checkEnemy) && random.nextInt(100) < 20) {
                            finalEnemyDmg = 200;
                            enemyCritType = 2;
                        } else if (isMystic(checkEnemy) && random.nextInt(100) < 2) {
                            finalEnemyDmg = 100; // 적 미스틱 야생몹도 2% 확률로 100딜 크리티컬!
                            enemyCritType = 1;
                        } else {
                            int baseEnemyDmg = (battle.getEnemyLevel() * 2) + (random.nextInt(3) + 1);
                            if (isLegendaryOrEpic(checkEnemy)) {
                                baseEnemyDmg += 5;
                            }
                            // ✨ 적 미스틱 야생몹 평타 시 상시 +2 보너스 데미지 가산
                            if (isMystic(checkEnemy)) {
                                baseEnemyDmg += 2;
                            }
                            finalEnemyDmg = (int) Math.round(baseEnemyDmg * enemyMultiplier);
                        }

                        int nextMyHp = Math.max(0, myActivePoke.getCurrentHp() - finalEnemyDmg);

                        myActivePoke.setCurrentHp(nextMyHp);
                        capturedPokemonRepository.saveAndFlush(myActivePoke);

                        res.put("myPokemonHp", nextMyHp);
                        res.put("myPokemonMaxHp", myActivePoke.getMaxHp());

                        StringBuilder roundLog = new StringBuilder();
                        if (myCritType == 2) {
                            roundLog.append("⚡⚡ [💥전설의 궁극기 폭발!!!] 나의 ").append(activePokeName).append("(이)가 신화 속 광선을 뿜어냈습니다! (💥고정 200 데미지!)\n");
                        } else if (myCritType == 1) {
                            roundLog.append("🔥✨ [💎미스틱 에이스 크리티컬!!!] 나의 ").append(activePokeName).append("의 진화 무기가 적을 관통했습니다! (💥고정 100 데미지!)\n");
                        } else {
                            roundLog.append("💥 ").append(activePokeName).append("의 [").append(mySkill).append("]! (").append(finalMyDmg).append(" 데미지!) ");
                            if (myMultiplier > 1.1) roundLog.append("✨ 효과가 굉장했다!\n");
                            else if (myMultiplier < 0.9) roundLog.append("💨 효과가 별로인 듯하다...\n");
                            else roundLog.append("\n");
                        }

                        if (enemyCritType == 2) {
                            roundLog.append("⚡⚡ [🚨🚨 경고 - 신의 격노!!!] 야생의 ").append(battle.getEnemyName()).append("(이)가 붉은 안광을 빛내며 필살기를 작렬했습니다! (💥고정 200 피해!)");
                        } else if (enemyCritType == 1) {
                            roundLog.append("🔥✨ [🚨 미스틱 카운터 발생!] 야생의 에이스 ").append(battle.getEnemyName()).append("(이)가 무서운 기세로 치명타를 내질렀습니다! (💥고정 100 피해!)");
                        } else {
                            roundLog.append("🏃‍♂️ 야생의 ").append(battle.getEnemyName()).append("의 [").append(enemySkill).append("] 반격! (").append(finalEnemyDmg).append(" 피해!) ");
                            if (enemyMultiplier > 1.1) roundLog.append("🔥 효과가 굉장했다!");
                            else if (enemyMultiplier < 0.9) roundLog.append("🍃 효과가 별로인 듯하다...");
                        }

                        if (nextMyHp <= 0) {
                            boolean hasAlivePokemon = false;
                            for (CapturedPokemon poke : myPokes) {
                                if (poke.getCurrentHp() > 0) {
                                    hasAlivePokemon = true;
                                    break;
                                }
                            }

                            if (hasAlivePokemon) {
                                roundLog.append("\n\n💀 ").append(activePokeName).append("이(가) 기절했습니다... 교체할 다른 포켓몬을 고르세요!");
                                res.put("log", roundLog.toString());
                                res.put("battleEnded", false);
                            } else {
                                roundLog.append("\n\n💀 모든 포켓몬이 기절했습니다!\n🏥 눈앞이 깜깜해졌다! 당신은 급히 마을 포켓몬센터로 이송되었습니다!");
                                res.put("log", roundLog.toString());
                                res.put("battleEnded", true);
                                activeBattles.remove(username);

                                authService.updatePlayerPosition(username, 240, 160, "town");

                                for (CapturedPokemon poke : myPokes) {
                                    poke.setCurrentHp(poke.getMaxHp());
                                }
                                capturedPokemonRepository.saveAllAndFlush(myPokes);

                                sendBroadcastUpdate(username, 240, 160, "town");
                            }
                        } else {
                            res.put("log", roundLog.toString());
                            res.put("battleEnded", false);
                        }
                    }
                }
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(res)));
            }

            // ⚔️ [PvP 신설 수신부 1]
            else if ("PVP_INVITE".equals(type)) {
                if (username == null) return;
                String targetUserId = (String) requestData.get("targetUserId");
                Map<String, Object> res = new HashMap<>();

                WebSocketSession targetSession = null;
                for (WebSocketSession s : sessions.values()) {
                    if (targetUserId != null && targetUserId.equals(s.getAttributes().get("username"))) {
                        targetSession = s;
                        break;
                    }
                }

                if (username.equals(targetUserId) || targetSession == null || !targetSession.isOpen() ||
                        activeBattles.containsKey(username) || userToRoomMap.containsKey(username) ||
                        activeBattles.containsKey(targetUserId) || userToRoomMap.containsKey(targetUserId)) {

                    res.put("type", "CHAT_MESSAGE");
                    res.put("id", "🔔 [시스템]");
                    res.put("msg", "상대방이 오프라인이거나 현재 다른 전투 중이라 신청을 보낼 수 없습니다.");
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(res)));
                    return;
                }

                String roomId = "ROOM_" + username + "_VS_" + targetUserId;
                PvPState pvpState = new PvPState(roomId, username, session, targetUserId, targetSession);
                activePvPMatches.put(roomId, pvpState);
                userToRoomMap.put(username, roomId);
                userToRoomMap.put(targetUserId, roomId);

                Map<String, Object> inviteForward = new HashMap<>();
                inviteForward.put("type", "PVP_INVITE_FORWARD");
                inviteForward.put("senderUserId", username);
                inviteForward.put("roomId", roomId);
                targetSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(inviteForward)));

                res.put("type", "CHAT_MESSAGE");
                res.put("id", "⚔️ [PvP]");
                res.put("msg", "[" + targetUserId + "] 트레이너에게 결투를 신청했습니다. 상대의 응답을 대기합니다.");
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(res)));
            }

            // ⚔️ [PvP 신설 수신부 2]
            else if ("PVP_RESPONSE".equals(type)) {
                if (username == null) return;
                String roomId = (String) requestData.get("roomId");
                String action = (String) requestData.get("action");

                PvPState pvp = activePvPMatches.get(roomId);
                if (pvp == null) return;

                if ("ACCEPT".equals(action)) {
                    Map<String, Object> selectCmd = new HashMap<>();
                    selectCmd.put("type", "PVP_INVITE_ACCEPTED_CHOOSE_STARTER");
                    selectCmd.put("roomId", roomId);

                    pvp.getP1Session().sendMessage(new TextMessage(objectMapper.writeValueAsString(selectCmd)));
                    pvp.getP2Session().sendMessage(new TextMessage(objectMapper.writeValueAsString(selectCmd)));
                    System.out.println("⚔️ [PvP 매칭 수락] 양측 트레이너 선발 포켓몬 지목 페이즈 진입: " + roomId);
                } else if ("REJECT".equals(action)) {
                    Map<String, Object> rejectPacket = new HashMap<>();
                    rejectPacket.put("type", "CHAT_MESSAGE");
                    rejectPacket.put("id", "⚔️ [PvP]");
                    rejectPacket.put("msg", "[" + username + "] 트레이너가 도전을 거절했습니다.");

                    if (pvp.getP1Session().isOpen())
                        pvp.getP1Session().sendMessage(new TextMessage(objectMapper.writeValueAsString(rejectPacket)));

                    userToRoomMap.remove(pvp.getP1Username());
                    userToRoomMap.remove(pvp.getP2Username());
                    activePvPMatches.remove(roomId);
                }
            }

            // 🔄 [J] 전투 커맨드: 출전 포켓몬 실시간 스위칭
            else if ("BATTLE_SWITCH".equals(type)) {
                if (username == null) return;

                if (userToRoomMap.containsKey(username)) {
                    Map<String, Object> pvpFail = new HashMap<>();
                    pvpFail.put("type", "BATTLE_ROUND_RESULT");
                    pvpFail.put("log", "❌ PvP 실시간 대인전에서는 현재 포켓몬 교체 기능이 지원되지 않습니다! 공격이나 항복을 고르세요.");
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(pvpFail)));
                    return;
                }

                if (!activeBattles.containsKey(username)) return;
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

                        myPokes.set(0, targetPoke);
                        myPokes.set(targetIndex, currentActivePoke);

                        battle.setPlayerActivePokemonUniqueId(targetPoke.getUniqueId());

                        if (currentActivePoke.getCurrentHp() <= 0) {
                            res.put("myPokemonName", newName);
                            res.put("myPokemonHp", targetPoke.getCurrentHp());
                            res.put("myPokemonMaxHp", targetPoke.getMaxHp());
                            res.put("log", "💀 기절한 " + oldName + " 대신...\n✨ 가라, " + newName + "!!! 전장으로 안전 투입!");
                            res.put("battleEnded", false);
                        } else {
                            int counterDmg = random.nextInt(3) + 1;
                            int finalNewHp = Math.max(0, targetPoke.getCurrentHp() - counterDmg);
                            targetPoke.setCurrentHp(finalNewHp);
                            capturedPokemonRepository.saveAndFlush(targetPoke);

                            res.put("myPokemonName", newName);
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

                Map<String, Object> res = new HashMap<>();
                res.put("type", "BATTLE_ROUND_RESULT");
                res.put("enemyHp", battle.getEnemyCurrentHp());

                java.util.List<CapturedPokemon> existingBag = capturedPokemonRepository.findByOwnerId(username);
                if (existingBag.size() >= 6) {
                    res.put("log", "🔴 몬스터볼을 던졌으나... 가방 한도(6마리)가 가득 찼습니다!\n📦 포획한 포켓몬은 수풀 속으로 도망쳐 버렸습니다.");
                    res.put("battleEnded", true);
                    activeBattles.remove(username);
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(res)));
                    return;
                }

                String enemyName = battle.getEnemyName().trim();
                double baseCatchRate = 0.40;

                if ("이상해씨".equals(enemyName) || "파이리".equals(enemyName) || "꼬부기".equals(enemyName) ||
                        "피카츄".equals(enemyName) || "망나뇽".equals(enemyName) || "마기라스".equals(enemyName)) {
                    baseCatchRate = 0.25;
                }
                else if (isLegendaryOrEpic(enemyName)) {
                    baseCatchRate = 0.04;
                }

                double hpRatio = (double) battle.getEnemyCurrentHp() / battle.getEnemyMaxHp();
                double finalCatchChance = baseCatchRate * (1.0 + (1.5 * (1.0 - hpRatio)));

                if (random.nextDouble() < finalCatchChance) {
                    CapturedPokemon cp = new CapturedPokemon();
                    cp.setOwnerId(username);
                    cp.setPokemonName(battle.getEnemyName());
                    cp.setLevel(battle.getEnemyLevel());
                    cp.setMaxHp(battle.getEnemyMaxHp());
                    cp.setCurrentHp(battle.getEnemyMaxHp());
                    capturedPokemonRepository.saveAndFlush(cp);

                    res.put("log", "🔴 몬스터볼을 던졌다! 대굴.. 대굴.. 탁!\n\n🎉 대성공! 무시무시한 난이도를 뚫고 [" + battle.getEnemyName() + "]을(를) 포획했습니다!");
                    res.put("battleEnded", true);
                    activeBattles.remove(username);
                } else {
                    if (isLegendaryOrEpic(enemyName)) {
                        res.put("log", "🔴 몬스터볼을 던졌다! ...쿠구궁!\n⚡ 전설의 포켓몬 [" + battle.getEnemyName() + "](이)가 압도적인 포스로 몬스터볼을 박살내며 탈출했습니다! (체력을 더 깎아야 합니다!)");
                    } else {
                        res.put("log", "🔴 몬스터볼을 던졌다! 아깝다! 포켓몬이 격렬하게 요동치며 탈출했습니다!");
                    }
                    res.put("battleEnded", false);
                }
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(res)));
            }

            // 🏃‍♂️ [F] 도망치기
            else if ("BATTLE_RUN".equals(type)) {
                if (username == null) return;

                if (userToRoomMap.containsKey(username)) {
                    String roomId = userToRoomMap.get(username);
                    PvPState pvp = activePvPMatches.get(roomId);
                    if (pvp != null) {
                        if (username.equals(pvp.getP1Username())) pvp.setP1Action("RUN");
                        if (username.equals(pvp.getP2Username())) pvp.setP2Action("RUN");
                        checkAndExecutePvPTurn(pvp);
                    }
                    return;
                }

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
                    }
                    capturedPokemonRepository.saveAllAndFlush(myPokemons);

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

            // 🍂 [파이널 신설 1]
            else if ("RELEASE_POKEMON".equals(type)) {
                if (username == null) return;
                long targetUniqueId = ((Number) requestData.get("pokemonId")).longValue();

                java.util.List<CapturedPokemon> myPokes = capturedPokemonRepository.findByOwnerId(username);

                if (myPokes.size() <= 1) {
                    Map<String, Object> errRes = new HashMap<>();
                    errRes.put("type", "CHAT_MESSAGE");
                    errRes.put("id", "🔔 [시스템]");
                    errRes.put("msg", "❌ 최후에 남은 한 마리의 포켓몬 파트너는 자연으로 방생할 수 없습니다!");
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(errRes)));
                    return;
                }

                capturedPokemonRepository.deleteById(targetUniqueId);
                capturedPokemonRepository.flush();

                java.util.List<CapturedPokemon> updatedPokes = capturedPokemonRepository.findByOwnerId(username);
                Map<String, Object> bagResponse = new HashMap<>();
                bagResponse.put("type", "BAG_RESULT");
                bagResponse.put("list", updatedPokes);
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(bagResponse)));

                Map<String, Object> chatRes = new HashMap<>();
                chatRes.put("type", "CHAT_MESSAGE");
                chatRes.put("id", "🍂 [방생]");
                chatRes.put("msg", "포켓몬을 드넓은 푸른 대자연의 서식지로 해방시켰습니다.");
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(chatRes)));
            }

            // 🥊 [파이널 신설 2]
            else if ("BATTLE_START_CONFIRM".equals(type)) {
                if (username == null) return;
                long selectedUniqueId = ((Number) requestData.get("pokemonId")).longValue();

                java.util.List<CapturedPokemon> myPokes = capturedPokemonRepository.findByOwnerId(username);
                CapturedPokemon chosenPoke = null;
                int chosenIndex = -1;

                for (int i = 0; i < myPokes.size(); i++) {
                    if (myPokes.get(i).getUniqueId() == selectedUniqueId) {
                        chosenPoke = myPokes.get(i);
                        chosenIndex = i;
                        break;
                    }
                }

                if (chosenPoke == null || chosenPoke.getCurrentHp() <= 0) {
                    Map<String, Object> errRes = new HashMap<>();
                    errRes.put("type", "CHAT_MESSAGE");
                    errRes.put("id", "🔔 [시스템]");
                    errRes.put("msg", "❌ 기절해 쓰러진 포켓몬은 선발 진형에 세울 수 없습니다! 건강한 포켓몬을 고르세요.");
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(errRes)));
                    return;
                }

                if (chosenIndex > 0) {
                    CapturedPokemon temp = myPokes.get(0);
                    myPokes.set(0, chosenPoke);
                    myPokes.set(chosenIndex, temp);
                }

                if (userToRoomMap.containsKey(username)) {
                    String roomId = userToRoomMap.get(username);
                    PvPState pvp = activePvPMatches.get(roomId);
                    if (pvp != null) {
                        if (username.equals(pvp.getP1Username())) pvp.setP1ActivePoke(chosenPoke);
                        if (username.equals(pvp.getP2Username())) pvp.setP2ActivePoke(chosenPoke);

                        if (pvp.getP1ActivePoke() != null && pvp.getP2ActivePoke() != null) {
                            pvp.setStatus("BATTLE");
                            sendPvPStartPacket(pvp);
                            System.out.println("⚔️ [PvP 라운드 오픈] 두 트레이너 모두 선발 확정 완료. 전장 개방: " + roomId);
                        } else {
                            Map<String, Object> waitRes = new HashMap<>();
                            waitRes.put("type", "CHAT_MESSAGE");
                            waitRes.put("id", "⚔️ [PvP]");
                            waitRes.put("msg", "[" + username + "] 트레이너가 출전 준비를 마쳤습니다. 상대방을 대기 중...");
                            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(waitRes)));
                        }
                    }
                    return;
                }

                if (!activeBattles.containsKey(username)) return;
                BattleState battle = activeBattles.get(username);
                battle.setPlayerActivePokemonUniqueId(chosenPoke.getUniqueId());

                Map<String, Object> encounterPacket = new HashMap<>();
                encounterPacket.put("type", "WILD_BATTLE_OPEN_FIELD");
                encounterPacket.put("pokemonName", battle.getEnemyName());
                encounterPacket.put("level", battle.getEnemyLevel());
                encounterPacket.put("hp", battle.getEnemyCurrentHp());
                encounterPacket.put("maxHp", battle.getEnemyMaxHp());
                encounterPacket.put("myPokemonHp", chosenPoke.getCurrentHp());
                encounterPacket.put("myPokemonMaxHp", chosenPoke.getMaxHp());
                encounterPacket.put("myActivePokeName", chosenPoke.getPokemonName());
                encounterPacket.put("myActivePokeLvl", chosenPoke.getLevel());

                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(encounterPacket)));
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
            if (userToRoomMap.containsKey(username)) {
                String roomId = userToRoomMap.get(username);
                PvPState pvp = activePvPMatches.get(roomId);
                if (pvp != null) {
                    Map<String, Object> esc = new HashMap<>();
                    esc.put("type", "BATTLE_ROUND_RESULT");
                    esc.put("battleEnded", true);
                    esc.put("log", "🏳️ 상대 트레이너가 겁을 먹고 서버에서 이탈했습니다! 당신의 부전승!");

                    if (username.equals(pvp.getP1Username()) && pvp.getP2Session().isOpen())
                        pvp.getP2Session().sendMessage(new TextMessage(objectMapper.writeValueAsString(esc)));
                    else if (username.equals(pvp.getP2Username()) && pvp.getP1Session().isOpen())
                        pvp.getP1Session().sendMessage(new TextMessage(objectMapper.writeValueAsString(esc)));

                    userToRoomMap.remove(pvp.getP1Username());
                    userToRoomMap.remove(pvp.getP2Username());
                    activePvPMatches.remove(roomId);
                }
            }

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

    // =========================================================================
    // ⚔️ [PvP 내부 연산 코어] (🚨 PvP 연산 시에도 미스틱 크리티컬 2% 동등 적용 완료)
    // =========================================================================
    private void sendPvPStartPacket(PvPState pvp) throws Exception {
        Map<String, Object> p1Packet = new HashMap<>();
        p1Packet.put("type", "PVP_START");
        p1Packet.put("roomId", pvp.getRoomId());
        p1Packet.put("myPokeName", pvp.getP1ActivePoke().getPokemonName());
        p1Packet.put("myPokeHp", pvp.getP1ActivePoke().getCurrentHp());
        p1Packet.put("myPokeMaxHp", pvp.getP1ActivePoke().getMaxHp());
        p1Packet.put("myPokeLvl", pvp.getP1ActivePoke().getLevel());
        p1Packet.put("enemyId", pvp.getP2Username());
        p1Packet.put("enemyPokeName", pvp.getP2ActivePoke().getPokemonName());
        p1Packet.put("enemyPokeHp", pvp.getP2ActivePoke().getCurrentHp());
        p1Packet.put("enemyPokeMaxHp", pvp.getP2ActivePoke().getMaxHp());
        p1Packet.put("enemyPokeLvl", pvp.getP2ActivePoke().getLevel());

        Map<String, Object> p2Packet = new HashMap<>();
        p2Packet.put("type", "PVP_START");
        p2Packet.put("roomId", pvp.getRoomId());
        p2Packet.put("myPokeName", pvp.getP2ActivePoke().getPokemonName());
        p2Packet.put("myPokeHp", pvp.getP2ActivePoke().getCurrentHp());
        p2Packet.put("myPokeMaxHp", pvp.getP2ActivePoke().getMaxHp());
        p2Packet.put("myPokeLvl", pvp.getP2ActivePoke().getLevel());
        p2Packet.put("enemyId", pvp.getP1Username());
        p2Packet.put("enemyPokeName", pvp.getP1ActivePoke().getPokemonName());
        p2Packet.put("enemyPokeHp", pvp.getP1ActivePoke().getCurrentHp());
        p2Packet.put("enemyPokeMaxHp", pvp.getP1ActivePoke().getMaxHp());
        p2Packet.put("enemyPokeLvl", pvp.getP1ActivePoke().getLevel());

        pvp.getP1Session().sendMessage(new TextMessage(objectMapper.writeValueAsString(p1Packet)));
        pvp.getP2Session().sendMessage(new TextMessage(objectMapper.writeValueAsString(p2Packet)));
    }

    private void checkAndExecutePvPTurn(PvPState pvp) throws Exception {
        if (pvp.getP1Action() == null || pvp.getP2Action() == null) return;

        Map<String, Object> p1Res = new HashMap<>();
        p1Res.put("type", "BATTLE_ROUND_RESULT");
        Map<String, Object> p2Res = new HashMap<>();
        p2Res.put("type", "BATTLE_ROUND_RESULT");
        StringBuilder p1Log = new StringBuilder("[Round " + pvp.getRound() + "]\n");
        StringBuilder p2Log = new StringBuilder("[Round " + pvp.getRound() + "]\n");

        if ("RUN".equals(pvp.getP1Action()) || "RUN".equals(pvp.getP2Action())) {
            if ("RUN".equals(pvp.getP1Action()) && "RUN".equals(pvp.getP2Action())) {
                p1Log.append("🏳️ 양측 트레이너의 동시 기권으로 승부가 무효화되었습니다.");
                p2Log.append("🏳️ 양측 트레이너의 동시 기권으로 승부가 무효화되었습니다.");
            } else if ("RUN".equals(pvp.getP1Action())) {
                p1Log.append("🏳️ 패배! 승부를 버리고 시합 도중 백기를 들었습니다.");
                p2Log.append("🏆 대승리! 상대 트레이너가 압박을 견디지 못하고 기권했습니다!");
            } else {
                p1Log.append("🏆 대승리! 상대 트레이너가 압박을 견디지 못하고 기권했습니다!");
                p2Log.append("🏳️ 패배! 승부를 버리고 시합 도중 백기를 들었습니다.");
            }
            p1Res.put("battleEnded", true);
            p2Res.put("battleEnded", true);
            p1Res.put("log", p1Log.toString());
            p2Res.put("log", p2Log.toString());
            sendPvPResponseAndClose(pvp, p1Res, p2Res);
            return;
        }

        String p1PokeName = pvp.getP1ActivePoke().getPokemonName().trim();
        String p2PokeName = pvp.getP2ActivePoke().getPokemonName().trim();

        String p1Skill = getSkillName(p1PokeName);
        String p2Skill = getSkillName(p2PokeName);

        double p1ToP2Multiplier = getMatchupMultiplier(p1PokeName, p2PokeName);
        double p2ToP1Multiplier = getMatchupMultiplier(p2PokeName, p1PokeName);

        // 💥 [P1 공격 데미지 연산] 필살기 체크 (전설 20%, 미스틱 2%)
        int finalP1Dmg;
        int p1CritType = 0; // 0: 일반, 1: 미스틱, 2: 전설
        if (isLegendaryOrEpic(p1PokeName) && random.nextInt(100) < 20) {
            finalP1Dmg = 200;
            p1CritType = 2;
        } else if (isMystic(p1PokeName) && random.nextInt(100) < 2) {
            finalP1Dmg = 100;
            p1CritType = 1;
        } else {
            int baseP1Dmg = (pvp.getP1ActivePoke().getLevel() * 2) + (random.nextInt(5) + 4);
            if (isMystic(p1PokeName)) baseP1Dmg += 3;
            finalP1Dmg = (int) Math.round(baseP1Dmg * p1ToP2Multiplier);
        }

        // 💥 [P2 공격 데미지 연산] 필살기 체크 (전설 20%, 미스틱 2%)
        int finalP2Dmg;
        int p2CritType = 0; // 0: 일반, 1: 미스틱, 2: 전설
        if (isLegendaryOrEpic(p2PokeName) && random.nextInt(100) < 20) {
            finalP2Dmg = 200;
            p2CritType = 2;
        } else if (isMystic(p2PokeName) && random.nextInt(100) < 2) {
            finalP2Dmg = 100;
            p2CritType = 1;
        } else {
            int baseP2Dmg = (pvp.getP2ActivePoke().getLevel() * 2) + (random.nextInt(5) + 4);
            if (isMystic(p2PokeName)) baseP2Dmg += 3;
            finalP2Dmg = (int) Math.round(baseP2Dmg * p2ToP1Multiplier);
        }

        boolean p1First = random.nextBoolean();

        if (p1First) {
            pvp.getP2ActivePoke().setCurrentHp(Math.max(0, pvp.getP2ActivePoke().getCurrentHp() - finalP1Dmg));

            if (p1CritType == 2) {
                p1Log.append("⚡⚡ [💥전설의 궁극기 폭발!!!] 나의 ").append(p1PokeName).append("의 궁극기 작렬! (💥고정 200 데미지!)\n");
                p2Log.append("🚨🚨 [🚨신의 격노 습격!!!] 상대 전설몬 ").append(p1PokeName).append("의 필살기 피격! (-200 HP)\n");
            } else if (p1CritType == 1) {
                p1Log.append("🔥✨ [💎미스틱 에이스 크리티컬!!!] 나의 ").append(p1PokeName).append("의 잠재 에너지가 가동되었습니다! (💥고정 100 데미지!)\n");
                p2Log.append("🔥✨ [🚨 미스틱 카운터 발생!] 상대 에이스 ").append(p1PokeName).append("의 뼈아픈 치명타 피격! (-100 HP)\n");
            } else {
                p1Log.append("⚔️ 나의 ").append(p1PokeName).append("의 [").append(p1Skill).append("]! (").append(finalP1Dmg).append(" 피해!) ");
                if (p1ToP2Multiplier > 1.1) p1Log.append("✨ 효과抜群!\n");
                else if (p1ToP2Multiplier < 0.9) p1Log.append("💨 효과미흡..\n");
                else p1Log.append("\n");
                p2Log.append("💥 상대 ").append(p1PokeName).append("의 [").append(p1Skill).append("] 습격! (-").append(finalP1Dmg).append(" HP)\n");
            }

            if (pvp.getP2ActivePoke().getCurrentHp() > 0) {
                pvp.getP1ActivePoke().setCurrentHp(Math.max(0, pvp.getP1ActivePoke().getCurrentHp() - finalP2Dmg));

                if (p2CritType == 2) {
                    p1Log.append("🚨🚨 [🚨신의 격노 습격!!!] 상대 전설몬 ").append(p2PokeName).append("의 필살기 반격 피격! (-200 HP)\n");
                    p2Log.append("⚡⚡ [💥전설의 궁극기 폭발!!!] 나의 ").append(p2PokeName).append("의 반격 궁극기 작렬! (💥고정 200 데미!)\n");
                } else if (p2CritType == 1) {
                    p1Log.append("🔥✨ [🚨 미스틱 카운터 발생!] 상대 에이스 ").append(p2PokeName).append("의 분노 섞인 반격 치명타! (-100 HP)\n");
                    p2Log.append("🔥✨ [💎미스틱 에이스 크리티컬!!!] 나의 ").append(p2PokeName).append("의 반격 에너지가 정밀 타격되었습니다! (💥고정 100 데미지!)\n");
                } else {
                    p1Log.append("💥 상대 ").append(p2PokeName).append("의 [").append(p2Skill).append("] 보복! (-").append(finalP2Dmg).append(" HP)\n");
                    p2Log.append("⚔️ 나의 ").append(p2PokeName).append("의 [").append(p2Skill).append("] 반격! (").append(finalP2Dmg).append(" 피해!) ");
                    if (p2ToP1Multiplier > 1.1) p2Log.append("✨ 효과抜群!\n");
                    else if (p2ToP1Multiplier < 0.9) p2Log.append("💨 효과미흡..\n");
                    else p2Log.append("\n");
                }
            } else {
                p1Log.append("🎉 상대 포켓몬을 처단하여 반격을 봉쇄했습니다!\n");
                p2Log.append("💀 포켓몬이 눈을 감아 반격 불가!\n");
            }
        } else {
            pvp.getP1ActivePoke().setCurrentHp(Math.max(0, pvp.getP1ActivePoke().getCurrentHp() - finalP2Dmg));

            if (p2CritType == 2) {
                p1Log.append("🚨🚨 [🚨신의 격노 기습!!!] 상대 전설몬 ").append(p2PokeName).append("의 필살기 선제 피격! (-200 HP)\n");
                p2Log.append("⚡⚡ [💥전설의 궁극기 폭발!!!] 나의 ").append(p2PokeName).append("의 선제 궁극기 폭발! (💥고정 200 데미지!)\n");
            } else if (p2CritType == 1) {
                p1Log.append("🔥✨ [🚨 미스틱 카운터 발생!] 상대 에이스 ").append(p2PokeName).append("에게 기습 치명타 피격! (-100 HP)\n");
                p2Log.append("🔥✨ [💎미스틱 에이스 크리티컬!!!] 나의 ").append(p2PokeName).append("의 선제 돌격 진화 치명타! (💥고정 100 데미지!)\n");
            } else {
                p1Log.append("💥 상대 ").append(p2PokeName).append("의 [").append(p2Skill).append("] 습격! (-").append(finalP2Dmg).append(" HP)\n");
                p2Log.append("⚔️ 나의 ").append(p2PokeName).append("의 [").append(p2Skill).append("]! (").append(finalP2Dmg).append(" 피해!) ");
                if (p2ToP1Multiplier > 1.1) p2Log.append("✨ 효과抜群!\n");
                else if (p2ToP1Multiplier < 0.9) p2Log.append("💨 효과미흡..\n");
                else p2Log.append("\n");
            }

            if (pvp.getP1ActivePoke().getCurrentHp() > 0) {
                pvp.getP2ActivePoke().setCurrentHp(Math.max(0, pvp.getP2ActivePoke().getCurrentHp() - finalP1Dmg));

                if (p1CritType == 2) {
                    p1Log.append("⚡⚡ [💥전설의 궁극기 폭발!!!] 나의 ").append(p1PokeName).append("의 보복 궁극기 발사! (💥고정 200 데미지!)\n");
                    p2Log.append("🚨🚨 [🚨신의 격노 습격!!!] 상대 전설몬 ").append(p1PokeName).append("의 보복 필살기 피격! (-200 HP)\n");
                } else if (p1CritType == 1) {
                    p1Log.append("🔥✨ [💎미스틱 에이스 크리티컬!!!] 나의 ").append(p1PokeName).append("의 분노 섞인 복수광선! (💥고정 100 데미지!)\n");
                    p2Log.append("🔥✨ [🚨 미스틱 카운터 발생!] 상대 에이스 ").append(p1PokeName).append("의 보복형 치명타 타격! (-100 HP)\n");
                } else {
                    p1Log.append("⚔️ 나의 ").append(p1PokeName).append("의 [").append(p1Skill).append("] 반격! (").append(finalP1Dmg).append(" 피해!) ");
                    if (p1ToP2Multiplier > 1.1) p1Log.append("✨ 효과抜群!\n");
                    else if (p1ToP2Multiplier < 0.9) p1Log.append("💨 효과미흡..\n");
                    else p1Log.append("\n");
                    p2Log.append("💥 상대 ").append(p1PokeName).append("의 [").append(p1Skill).append("] 보복! (-").append(finalP1Dmg).append(" HP)\n");
                }
            } else {
                p1Log.append("💀 내 포켓몬이 기절해 반격 불가!\n");
                p2Log.append("🎉 선제 공격으로 상대의 반격을 완벽히 차단했습니다!\n");
            }
        }

        // JPA 영속성 반영
        capturedPokemonRepository.saveAndFlush(pvp.getP1ActivePoke());
        capturedPokemonRepository.saveAndFlush(pvp.getP2ActivePoke());

        p1Res.put("myPokemonHp", pvp.getP1ActivePoke().getCurrentHp());
        p1Res.put("myPokemonMaxHp", pvp.getP1ActivePoke().getMaxHp());
        p1Res.put("enemyHp", pvp.getP2ActivePoke().getCurrentHp());
        p2Res.put("myPokemonHp", pvp.getP2ActivePoke().getCurrentHp());
        p2Res.put("myPokemonMaxHp", pvp.getP2ActivePoke().getMaxHp());
        p2Res.put("enemyHp", pvp.getP1ActivePoke().getCurrentHp());

        boolean p1Fainted = pvp.getP1ActivePoke().getCurrentHp() <= 0;
        boolean p2Fainted = pvp.getP2ActivePoke().getCurrentHp() <= 0;

        if (p1Fainted || p2Fainted) {
            p1Res.put("battleEnded", true);
            p2Res.put("battleEnded", true);
            if (p1Fainted && p2Fainted) {
                p1Log.append("\n💀 무승부! 양측 포켓몬이 동시에 무너져 시합이 종결되었습니다.");
                p2Log.append("\n💀 무승부! 양측 포켓몬이 동시에 무너져 시합이 종결되었습니다.");
            } else if (p1Fainted) {
                p1Log.append("\n💀 내 포켓몬이 기절했습니다.. PvP 대인전 패배.");
                p2Log.append("\n🏆 축하합니다! 상대를 완벽히 짓누르고 PvP 챔피언에 등극했습니다!");
            } else {
                p1Log.append("\n🏆 축하합니다! 상대를 완벽히 짓누르고 PvP 챔피언에 등극했습니다!");
                p2Log.append("\n💀 내 포켓몬이 기절했습니다.. 대인전 PvP 패배.");
            }
            p1Res.put("log", p1Log.toString());
            p2Res.put("log", p2Log.toString());
            sendPvPResponseAndClose(pvp, p1Res, p2Res);
        } else {
            p1Res.put("battleEnded", false);
            p2Res.put("battleEnded", false);
            p1Log.append("\n 다음 명령을 대기합니다.");
            p2Log.append("\n 다음 명령을 입력하세요.");
            p1Res.put("log", p1Log.toString());
            p2Res.put("log", p2Log.toString());
            pvp.setP1Action(null);
            pvp.setP2Action(null);
            pvp.setRound(pvp.getRound() + 1);

            pvp.getP1Session().sendMessage(new TextMessage(objectMapper.writeValueAsString(p1Res)));
            pvp.getP2Session().sendMessage(new TextMessage(objectMapper.writeValueAsString(p2Res)));
        }
    }

    private void sendPvPResponseAndClose(PvPState pvp, Map<String, Object> p1Res, Map<String, Object> p2Res) throws Exception {
        pvp.getP1Session().sendMessage(new TextMessage(objectMapper.writeValueAsString(p1Res)));
        pvp.getP2Session().sendMessage(new TextMessage(objectMapper.writeValueAsString(p2Res)));
        userToRoomMap.remove(pvp.getP1Username());
        userToRoomMap.remove(pvp.getP2Username());
        activePvPMatches.remove(pvp.getRoomId());
        System.out.println("⚔️ [PvP 전장 종료] 세션 및 매칭 룸 정상 폐쇄 완수: " + pvp.getRoomId());
    }

    // =========================================================================
    // 📊 [히든 유틸리티 서브 도킹 메소드]
    // =========================================================================

    // 👑 [기믹 적용]: 프론트엔드와 싱크를 맞춘 미스틱 등급 판정 가드 필터 함수 추가
    private boolean isMystic(String pokemonName) {
        if (pokemonName == null) return false;
        String name = pokemonName.trim();
        return "미뇽".equals(name) || "잠만보".equals(name) || "켄타로스".equals(name) ||
                "라프라스".equals(name) || "리자몽".equals(name) || "거북왕".equals(name) ||
                "이상해꽃".equals(name) || "망나뇽".equals(name) || "마기라스".equals(name);
    }

    private boolean isLegendaryOrEpic(String pokemonName) {
        if (pokemonName == null) return false;
        String name = pokemonName.trim();
        return "뮤츠".equals(name) || "루기아".equals(name) || "칠색조".equals(name) ||
                "앤테이".equals(name) || "라이코".equals(name) || "스이쿤".equals(name) ||
                "썬더".equals(name) || "뮤".equals(name) || "세레비".equals(name);
    }

    private String getSkillName(String pokemonName) {
        String name = pokemonName.trim();

        // 🔥 불꽃 타입 기술
        if ("파이리".equals(name) || "리자몽".equals(name) || "가디".equals(name) ||
                "브케인".equals(name) || "앤테이".equals(name) || "칠색조".equals(name)) {
            return "화염방사";
        }
        // 💧 물 타입 기술
        if ("꼬부기".equals(name) || "거북왕".equals(name) || "발챙이".equals(name) ||
                "라프라스".equals(name) || "리아코".equals(name) || "스이쿤".equals(name)) {
            return "하이드로펌프";
        }
        // 🍃 풀 타입 기술
        if ("이상해씨".equals(name) || "이상해꽃".equals(name) || "뚜벅초".equals(name) ||
                "치코리타".equals(name) || "세레비".equals(name)) {
            return "솔라빔";
        }
        // ⚡ 전기 타입 기술
        if ("피카츄".equals(name) || "피츄".equals(name) || "썬더".equals(name) ||
                "라이코".equals(name) || "전룡".equals(name)) {
            return "10만볼트";
        }
        // 🔮 에스퍼/에픽 타입 기술
        if ("뮤츠".equals(name) || "뮤".equals(name) || "루기아".equals(name) ||
                "망나뇽".equals(name) || "마기라스".equals(name)) {
            return "파괴광선";
        }

        return "몸통박치기"; // 노말/기타 포켓몬 (구구, 꼬렛, 토게피 등)
    }

    private double getMatchupMultiplier(String attacker, String defender) {
        String attType = getPokemonType(attacker.trim());
        String defType = getPokemonType(defender.trim());

        if ("WATER".equals(attType) && "FIRE".equals(defType)) return 1.5;
        if ("FIRE".equals(attType) && "GRASS".equals(defType)) return 1.5;
        if ("GRASS".equals(attType) && "WATER".equals(defType)) return 1.5;

        if ("FIRE".equals(attType) && "WATER".equals(defType)) return 0.5;
        if ("GRASS".equals(attType) && "FIRE".equals(defType)) return 0.5;
        if ("WATER".equals(attType) && "GRASS".equals(defType)) return 0.5;

        return 1.0;
    }

    private String getPokemonType(String name) {
        String n = name.trim();
        if ("파이리".equals(n) || "리자몽".equals(n) || "가디".equals(n) || "브케인".equals(n) || "앤테이".equals(n) || "칠색조".equals(n))
            return "FIRE";
        if ("꼬부기".equals(n) || "거북왕".equals(n) || "발챙이".equals(n) || "라프라스".equals(n) || "리아코".equals(n) || "스이쿤".equals(n))
            return "WATER";
        if ("이상해씨".equals(n) || "이상해꽃".equals(n) || "뚜벅초".equals(n) || "치코리타".equals(n) || "세레비".equals(n))
            return "GRASS";
        if ("피카츄".equals(n) || "피츄".equals(n) || "썬더".equals(n) || "라이코".equals(n) || "전룡".equals(n)) return "ELECTRIC";
        if ("뮤츠".equals(n) || "뮤".equals(n) || "루기아".equals(n) || "망나뇽".equals(n) || "마기라스".equals(n)) return "EPIC";

        return "NORMAL";
    }
}