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

    // ⚔️ [PvP 메모리 주머니]: 실시간 유저 대인전 세션 및 방 점유 추적용 고속 보관소
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

            // 🏃‍♂️ [C] 캐릭터 이동 요청 (🚨 5. 전설의 포켓몬 레벨 및 공격력/체력 레이드 격상 기믹 도킹 완료)
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

                                // 👑 [기믹 5 적용] 초전설 보스 격상 명세 (뮤츠, 루기아, 칠색조 대격변 레이드 스펙 사출)
                                String checkName = encounteredPokemon.trim();
                                if ("뮤츠".equals(checkName) || "루기아".equals(checkName) || "칠색조".equals(checkName)) {
                                    finalLevel = random.nextInt(6) + 15; // 보스다운 위엄: Lv.15 ~ Lv.20
                                    finalHp = 100 + (finalLevel * 2);    // 엄청난 통뼈 피통 레이드화: HP 130 ~ 140 돌파
                                    System.out.println("👑 [초전설 레이드 보스 강림!] " + checkName + " Lv." + finalLevel + " (HP: " + finalHp + ")");
                                } else {
                                    // 일반 야생 포켓몬 스펙
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

            // 🥊 [D] 전투 커맨드: 공격하기 (🚨 3. 레벨 공격력 수식 비례 스케일링 & 4. 속성 기술 및 상성 배율 완벽 융합)
            else if ("BATTLE_ATTACK".equals(type)) {
                if (username == null) return;

                // ⚔️ [분기 1]: 실시간 유저 간 대인전(PvP) 공격 커맨드 정산 구역
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

                // 🌾 [분기 2]: 기존 독립 야생 포켓몬 배틀 공격 정산 구역
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

                    // 💥 [기믹 3 & 4 결합 단도리] 내 아군 선제 속성 공격 계산 스케일링 수식 연산 가동
                    String mySkill = getSkillName(activePokeName);
                    double myMultiplier = getMatchupMultiplier(activePokeName, battle.getEnemyName());

                    // 레벨 비례형 스케일링 기본 타격 대미지 공식: (레벨 * 2) + random(4~8)
                    int baseMyDmg = (myActivePoke.getLevel() * 2) + (random.nextInt(5) + 4);
                    int finalMyDmg = (int) Math.round(baseMyDmg * myMultiplier);

                    battle.setEnemyCurrentHp(Math.max(0, battle.getEnemyCurrentHp() - finalMyDmg));
                    res.put("enemyHp", battle.getEnemyCurrentHp());
                    res.put("myPokemonName", activePokeName);

                    // 🏆 [야생 포켓몬 격파 -> 경험치 및 레벨업 정산 시퀀스]
                    if (battle.getEnemyCurrentHp() <= 0) {
                        StringBuilder logBuilder = new StringBuilder();
                        logBuilder.append("💥 ").append(activePokeName).append("의 [").append(mySkill).append("] 공격! (").append(finalMyDmg).append(" 데미지!) ");
                        if (myMultiplier > 1.1) logBuilder.append("✨ 효과가 굉장했다!\n\n");
                        else if (myMultiplier < 0.9) logBuilder.append("💨 효과가 별로인 듯하다...\n\n");
                        else logBuilder.append("\n\n");

                        logBuilder.append("🎉 야생의 ").append(battle.getEnemyName()).append("이(가) 쓰러졌다!\n");

                        int gainedExp = battle.getEnemyLevel() * 5;
                        myActivePoke.setExp(myActivePoke.getExp() + gainedExp);
                        logBuilder.append("✨ ").append(activePokeName).append("은(는) ").append(gainedExp).append(" XP의 경험치를 획득했다! ");

                        int neededExp = myActivePoke.getLevel() * 20;
                        boolean leveledUp = false;

                        while (myActivePoke.getExp() >= neededExp) {
                            myActivePoke.setExp(myActivePoke.getExp() - neededExp); // 요구치 차감
                            myActivePoke.setLevel(myActivePoke.getLevel() + 1);    // 레벨 1 상승
                            myActivePoke.setMaxHp(myActivePoke.getMaxHp() + 3);     // 최대 체력 +3 보상
                            myActivePoke.setCurrentHp(myActivePoke.getMaxHp());     // 보너스로 체력 전원 완치!

                            leveledUp = true;
                            neededExp = myActivePoke.getLevel() * 20; // 다음 레벨 요구치 갱신
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
                    // 🔄 야생 포켓몬 반격 타임 (스케일링 및 전설 보스 가중 타격 주입)
                    else {
                        String enemySkill = getSkillName(battle.getEnemyName());
                        double enemyMultiplier = getMatchupMultiplier(battle.getEnemyName(), activePokeName);

                        // 레벨 기반 적 타격 공식: (적레벨 * 2) + random(1~3)
                        int baseEnemyDmg = (battle.getEnemyLevel() * 2) + (random.nextInt(3) + 1);

                        // 👑 초전설 보스급 포켓몬 반격시 추가 파괴력 보정 보너스 가중치(+5 데미지 추가!)
                        String checkEnemy = battle.getEnemyName().trim();
                        if ("뮤츠".equals(checkEnemy) || "루기아".equals(checkEnemy) || "칠색조".equals(checkEnemy)) {
                            baseEnemyDmg += 5;
                        }

                        int finalEnemyDmg = (int) Math.round(baseEnemyDmg * enemyMultiplier);
                        int nextMyHp = Math.max(0, myActivePoke.getCurrentHp() - finalEnemyDmg);

                        myActivePoke.setCurrentHp(nextMyHp);
                        capturedPokemonRepository.saveAndFlush(myActivePoke);

                        res.put("myPokemonHp", nextMyHp);
                        res.put("myPokemonMaxHp", myActivePoke.getMaxHp());

                        StringBuilder roundLog = new StringBuilder();
                        roundLog.append("💥 ").append(activePokeName).append("의 [").append(mySkill).append("]! (").append(finalMyDmg).append(" 데미지!) ");
                        if (myMultiplier > 1.1) roundLog.append("✨ 효과가 굉장했다!\n");
                        else if (myMultiplier < 0.9) roundLog.append("💨 효과가 별로인 듯하다...\n");
                        else roundLog.append("\n");

                        roundLog.append("🏃‍♂️ 야생의 ").append(battle.getEnemyName()).append("의 [").append(enemySkill).append("] 반격! (").append(finalEnemyDmg).append(" 피해!) ");
                        if (enemyMultiplier > 1.1) roundLog.append("🔥 효과가 굉장했다!");
                        else if (enemyMultiplier < 0.9) roundLog.append("🍃 효과가 별로인 듯하다...");

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
                                    capturedPokemonRepository.saveAndFlush(poke);
                                }
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

            // ⚔️ [PvP 신설 수신부 1]: 타 플레이어 결투 신청 패킷 접수처
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

                    res.put("type", "CHAT_MESSAGE"); res.put("id", "🔔 [시스템]");
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

                res.put("type", "CHAT_MESSAGE"); res.put("id", "⚔️ [PvP]");
                res.put("msg", "[" + targetUserId + "] 트레이너에게 결투를 신청했습니다. 상대의 응답을 대기합니다.");
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(res)));
            }

            // ⚔️ [PvP 신설 수신부 2]: 초대 받은 유저의 수락(ACCEPT) / 거절(REJECT) 정산 통로
            else if ("PVP_RESPONSE".equals(type)) {
                if (username == null) return;
                String roomId = (String) requestData.get("roomId");
                String action = (String) requestData.get("action");

                PvPState pvp = activePvPMatches.get(roomId);
                if (pvp == null) return;

                if ("ACCEPT".equals(action)) {
                    // 방 상태는 매칭 완료(WAITING)인 채로 유지하고, 양측 브라우저에 가방을 열어 선발을 고르라고 명령 사출!
                    Map<String, Object> selectCmd = new HashMap<>();
                    selectCmd.put("type", "PVP_INVITE_ACCEPTED_CHOOSE_STARTER");
                    selectCmd.put("roomId", roomId);

                    pvp.getP1Session().sendMessage(new TextMessage(objectMapper.writeValueAsString(selectCmd)));
                    pvp.getP2Session().sendMessage(new TextMessage(objectMapper.writeValueAsString(selectCmd)));
                    System.out.println("⚔️ [PvP 매칭 수락] 양측 트레이너 선발 포켓몬 지목 페이즈 진입: " + roomId);
                }
                else if ("REJECT".equals(action)) {
                    Map<String, Object> rejectPacket = new HashMap<>();
                    rejectPacket.put("type", "CHAT_MESSAGE"); rejectPacket.put("id", "⚔️ [PvP]");
                    rejectPacket.put("msg", "[" + username + "] 트레이너가 도전을 거절했습니다.");

                    if (pvp.getP1Session().isOpen()) pvp.getP1Session().sendMessage(new TextMessage(objectMapper.writeValueAsString(rejectPacket)));

                    userToRoomMap.remove(pvp.getP1Username()); userToRoomMap.remove(pvp.getP2Username());
                    activePvPMatches.remove(roomId);
                }
            }

            // 🔄 [J] 전투 커맨드: 출전 포켓몬 실시간 스위칭 (🚨 PvP 상황 시 교체 락 가드 반영)
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

            // 🔴 [E] 몬스터볼 포획 (🚨 2. 가방 상한 제한선 딱 6마리 하드락 패치 장착 완료)
            else if ("BATTLE_CATCH".equals(type)) {
                if (username == null || !activeBattles.containsKey(username)) return;
                BattleState battle = activeBattles.get(username);

                Map<String, Object> res = new HashMap<>();
                res.put("type", "BATTLE_ROUND_RESULT");
                res.put("enemyHp", battle.getEnemyCurrentHp());

                // 📦 [기믹 2 적용] 소지 한도 6마리 스크리닝 가드 검사
                java.util.List<CapturedPokemon> existingBag = capturedPokemonRepository.findByOwnerId(username);
                if (existingBag.size() >= 6) {
                    res.put("log", "🔴 몬스터볼을 힘차게 던졌으나... 앗!\n📦 트레이너가 소지할 수 있는 가방 한도(6마리)가 가득 차서 넘쳐흐릅니다!\n포획된 포켓몬은 더 이상 들어가지 못하고 수풀 속으로 슬피 도망쳐 버렸습니다. (우측 가방 탭에서 방생을 통해 자리를 비우세요.)");
                    res.put("battleEnded", true);
                    activeBattles.remove(username);
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(res)));
                    return;
                }

                double hpRatio = (double) battle.getEnemyCurrentHp() / battle.getEnemyMaxHp();
                double catchChance = 0.30 + (0.50 * (1.0 - hpRatio));

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

            // 🍂 [파이널 신설 1]: 가방 한도 해제를 위한 개별 포켓몬 영구 방생 처리 인터셉터
            else if ("RELEASE_POKEMON".equals(type)) {
                if (username == null) return;
                long targetUniqueId = ((Number) requestData.get("pokemonId")).longValue();

                java.util.List<CapturedPokemon> myPokes = capturedPokemonRepository.findByOwnerId(username);

                if (myPokes.size() <= 1) {
                    Map<String, Object> errRes = new HashMap<>();
                    errRes.put("type", "CHAT_MESSAGE"); errRes.put("id", "🔔 [시스템]");
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
                chatRes.put("type", "CHAT_MESSAGE"); chatRes.put("id", "🍂 [방생]");
                chatRes.put("msg", "포켓몬을 드넓은 푸른 대자연의 서식지로 해방시켰습니다.");
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(chatRes)));
            }

            // 🥊 [파이널 신설 2]: 유저가 조우 스크린에서 선발 출전 투수를 확정 지었을 때 정식 배틀 소환 개막
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
                    errRes.put("type", "CHAT_MESSAGE"); errRes.put("id", "🔔 [시스템]");
                    errRes.put("msg", "❌ 기절해 쓰러진 포켓몬은 선발 진형에 세울 수 없습니다! 건강한 포켓몬을 고르세요.");
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(errRes)));
                    return;
                }

                // 1선(가방 0번째)으로 스왑 정렬
                if (chosenIndex > 0) {
                    CapturedPokemon temp = myPokes.get(0);
                    myPokes.set(0, chosenPoke);
                    myPokes.set(chosenIndex, temp);
                }

                // ⚔️ 케이스 A: 만약 이 유저가 현재 'PvP 방'에 소속되어 있다면?
                if (userToRoomMap.containsKey(username)) {
                    String roomId = userToRoomMap.get(username);
                    PvPState pvp = activePvPMatches.get(roomId);
                    if (pvp != null) {
                        // 누가 확정했냐에 따라 포켓몬 데이터 안착
                        if (username.equals(pvp.getP1Username())) pvp.setP1ActivePoke(chosenPoke);
                        if (username.equals(pvp.getP2Username())) pvp.setP2ActivePoke(chosenPoke);

                        // 📡 양쪽 다 포켓몬 선택을 완료했다면 비로소 전장 동시 개막!!
                        if (pvp.getP1ActivePoke() != null && pvp.getP2ActivePoke() != null) {
                            pvp.setStatus("BATTLE"); // 룸 가동
                            sendPvPStartPacket(pvp);
                            System.out.println("⚔️ [PvP 라운드 오픈] 두 트레이너 모두 선발 확정 완료. 전장 개방: " + roomId);
                        } else {
                            // 한 명만 골랐다면 시스템 메시지로 안내
                            Map<String, Object> waitRes = new HashMap<>();
                            waitRes.put("type", "CHAT_MESSAGE"); waitRes.put("id", "⚔️ [PvP]");
                            waitRes.put("msg", "[" + username + "] 트레이너가 출전 준비를 마쳤습니다. 상대방을 대기 중...");
                            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(waitRes)));
                        }
                    }
                    return;
                }

                // 🌾 케이스 B: PvP가 아니라면 기존 야생 포켓몬 배틀 개막 처리
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
                    esc.put("type", "BATTLE_ROUND_RESULT"); esc.put("battleEnded", true);
                    esc.put("log", "🏳️ 상대 트레이너가 겁을 먹고 서버에서 이탈했습니다! 당신의 부전승!");

                    if (username.equals(pvp.getP1Username()) && pvp.getP2Session().isOpen()) pvp.getP2Session().sendMessage(new TextMessage(objectMapper.writeValueAsString(esc)));
                    else if (username.equals(pvp.getP2Username()) && pvp.getP1Session().isOpen()) pvp.getP1Session().sendMessage(new TextMessage(objectMapper.writeValueAsString(esc)));

                    userToRoomMap.remove(pvp.getP1Username()); userToRoomMap.remove(pvp.getP2Username());
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
    // ⚔️ [PvP 내부 연산 코어]: 멀티플레이어 실시간 동시성 턴제 정산 핵심 로직스
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

        Map<String, Object> p1Res = new HashMap<>(); p1Res.put("type", "BATTLE_ROUND_RESULT");
        Map<String, Object> p2Res = new HashMap<>(); p2Res.put("type", "BATTLE_ROUND_RESULT");
        StringBuilder p1Log = new StringBuilder("[Round " + pvp.getRound() + "]\n");
        StringBuilder p2Log = new StringBuilder("[Round " + pvp.getRound() + "]\n");

        // 🏳️ 1. 기권/항복 유저 검수 분기
        if ("RUN".equals(pvp.getP1Action()) || "RUN".equals(pvp.getP2Action())) {
            if ("RUN".equals(pvp.getP1Action()) && "RUN".equals(pvp.getP2Action())) {
                p1Log.append("🏳️ 양측 트레이너의 동시 기권으로 승부가 무효화되었습니다."); p2Log.append("🏳️ 양측 트레이너의 동시 기권으로 승부가 무효화되었습니다.");
            } else if ("RUN".equals(pvp.getP1Action())) {
                p1Log.append("🏳️ 패배! 승부를 버리고 시합 도중 백기를 들었습니다."); p2Log.append("🏆 대승리! 상대 트레이너가 압박을 견디지 못하고 기권했습니다!");
            } else {
                p1Log.append("🏆 대승리! 상대 트레이너가 압박을 견디지 못하고 기권했습니다!"); p2Log.append("🏳️ 패배! 승부를 버리고 시합 도중 백기를 들었습니다.");
            }
            p1Res.put("battleEnded", true); p2Res.put("battleEnded", true);
            p1Res.put("log", p1Log.toString()); p2Res.put("log", p2Log.toString());
            sendPvPResponseAndClose(pvp, p1Res, p2Res);
            return;
        }

        // ⚔️ 2. 리얼타임 PvP 동시 공방 난수 연산 (🚨 3 & 4 기믹 주입 수식 정산 가동)
        String p1Skill = getSkillName(pvp.getP1ActivePoke().getPokemonName());
        String p2Skill = getSkillName(pvp.getP2ActivePoke().getPokemonName());

        double p1ToP2Multiplier = getMatchupMultiplier(pvp.getP1ActivePoke().getPokemonName(), pvp.getP2ActivePoke().getPokemonName());
        double p2ToP1Multiplier = getMatchupMultiplier(pvp.getP2ActivePoke().getPokemonName(), pvp.getP1ActivePoke().getPokemonName());

        // 양측 레벨에 따른 스케일링 데미지 공식 동등 적용
        int baseP1Dmg = (pvp.getP1ActivePoke().getLevel() * 2) + (random.nextInt(5) + 4);
        int finalP1Dmg = (int) Math.round(baseP1Dmg * p1ToP2Multiplier);

        int baseP2Dmg = (pvp.getP2ActivePoke().getLevel() * 2) + (random.nextInt(5) + 4);
        int finalP2Dmg = (int) Math.round(baseP2Dmg * p2ToP1Multiplier);

        boolean p1First = random.nextBoolean();

        if (p1First) {
            pvp.getP2ActivePoke().setCurrentHp(Math.max(0, pvp.getP2ActivePoke().getCurrentHp() - finalP1Dmg));
            p1Log.append("⚔️ 나의 ").append(pvp.getP1ActivePoke().getPokemonName()).append("의 [").append(p1Skill).append("]! (").append(finalP1Dmg).append(" 피해!) ");
            if (p1ToP2Multiplier > 1.1) p1Log.append("✨ 효과抜群!\n"); else if (p1ToP2Multiplier < 0.9) p1Log.append("💨 효과미흡..\n"); else p1Log.append("\n");

            p2Log.append("💥 상대 ").append(pvp.getP1ActivePoke().getPokemonName()).append("의 [").append(p1Skill).append("] 습격! (-").append(finalP1Dmg).append(" HP)\n");

            if (pvp.getP2ActivePoke().getCurrentHp() > 0) {
                pvp.getP1ActivePoke().setCurrentHp(Math.max(0, pvp.getP1ActivePoke().getCurrentHp() - finalP2Dmg));
                p1Log.append("💥 상대 ").append(pvp.getP2ActivePoke().getPokemonName()).append("의 [").append(p2Skill).append("] 보복! (-").append(finalP2Dmg).append(" HP)\n");

                p2Log.append("⚔️ 나의 ").append(pvp.getP2ActivePoke().getPokemonName()).append("의 [").append(p2Skill).append("] 반격! (").append(finalP2Dmg).append(" 피해!) ");
                if (p2ToP1Multiplier > 1.1) p2Log.append("✨ 효과抜群!\n"); else if (p2ToP1Multiplier < 0.9) p2Log.append("💨 효과미흡..\n"); else p2Log.append("\n");
            } else { p2Log.append("💀 포켓몬이 기절해 반격 불가!\n"); }
        } else {
            pvp.getP1ActivePoke().setCurrentHp(Math.max(0, pvp.getP1ActivePoke().getCurrentHp() - finalP2Dmg));
            p1Log.append("💥 상대 ").append(pvp.getP2ActivePoke().getPokemonName()).append("의 [").append(p2Skill).append("] 습격! (-").append(finalP2Dmg).append(" HP)\n");

            p2Log.append("⚔️ 나의 ").append(pvp.getP2ActivePoke().getPokemonName()).append("의 [").append(p2Skill).append("]! (").append(finalP2Dmg).append(" 피해!) ");
            if (p2ToP1Multiplier > 1.1) p2Log.append("✨ 효과抜群!\n"); else if (p2ToP1Multiplier < 0.9) p2Log.append("💨 효과미흡..\n"); else p2Log.append("\n");

            if (pvp.getP1ActivePoke().getCurrentHp() > 0) {
                pvp.getP2ActivePoke().setCurrentHp(Math.max(0, pvp.getP2ActivePoke().getCurrentHp() - finalP1Dmg));
                p1Log.append("⚔️ 나의 ").append(pvp.getP1ActivePoke().getPokemonName()).append("의 [").append(p1Skill).append("] 반격! (").append(finalP1Dmg).append(" 피해!) ");
                if (p1ToP2Multiplier > 1.1) p1Log.append("✨ 효과抜群!\n"); else if (p1ToP2Multiplier < 0.9) p1Log.append("💨 효과미흡..\n"); else p1Log.append("\n");

                p2Log.append("💥 상대 ").append(pvp.getP1ActivePoke().getPokemonName()).append("의 [").append(p1Skill).append("] 보복! (-").append(finalP1Dmg).append(" HP)\n");
            } else { p1Log.append("💀 포켓몬이 기절해 반격 불가!\n"); }
        }

        capturedPokemonRepository.saveAndFlush(pvp.getP1ActivePoke());
        capturedPokemonRepository.saveAndFlush(pvp.getP2ActivePoke());

        p1Res.put("myPokemonHp", pvp.getP1ActivePoke().getCurrentHp()); p1Res.put("myPokemonMaxHp", pvp.getP1ActivePoke().getMaxHp()); p1Res.put("enemyHp", pvp.getP2ActivePoke().getCurrentHp());
        p2Res.put("myPokemonHp", pvp.getP2ActivePoke().getCurrentHp()); p2Res.put("myPokemonMaxHp", pvp.getP2ActivePoke().getMaxHp()); p2Res.put("enemyHp", pvp.getP1ActivePoke().getCurrentHp());

        boolean p1Fainted = pvp.getP1ActivePoke().getCurrentHp() <= 0;
        boolean p2Fainted = pvp.getP2ActivePoke().getCurrentHp() <= 0;

        if (p1Fainted || p2Fainted) {
            p1Res.put("battleEnded", true); p2Res.put("battleEnded", true);
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
            p1Res.put("log", p1Log.toString()); p2Res.put("log", p2Log.toString());
            sendPvPResponseAndClose(pvp, p1Res, p2Res);
        } else {
            p1Res.put("battleEnded", false); p2Res.put("battleEnded", false);
            p1Log.append("\n 다음 명령을 대기합니다."); p2Log.append("\n 다음 명령을 입력하세요.");
            p1Res.put("log", p1Log.toString()); p2Res.put("log", p2Log.toString());
            pvp.setP1Action(null); pvp.setP2Action(null); pvp.setRound(pvp.getRound() + 1);

            pvp.getP1Session().sendMessage(new TextMessage(objectMapper.writeValueAsString(p1Res)));
            pvp.getP2Session().sendMessage(new TextMessage(objectMapper.writeValueAsString(p2Res)));
        }
    }

    private void sendPvPResponseAndClose(PvPState pvp, Map<String, Object> p1Res, Map<String, Object> p2Res) throws Exception {
        pvp.getP1Session().sendMessage(new TextMessage(objectMapper.writeValueAsString(p1Res)));
        pvp.getP2Session().sendMessage(new TextMessage(objectMapper.writeValueAsString(p2Res)));
        userToRoomMap.remove(pvp.getP1Username()); userToRoomMap.remove(pvp.getP2Username());
        activePvPMatches.remove(pvp.getRoomId());
        System.out.println("⚔️ [PvP 전장 종료] 세션 및 매칭 룸 정상 폐쇄 완수: " + pvp.getRoomId());
    }

    // =========================================================================
    // 📊 [히든 유틸리티 서브 도킹 메소드] 시그니처 4대 속성 스킬 배정 및 상성 가중치 밸브
    // =========================================================================
    private String getSkillName(String pokemonName) {
        String name = pokemonName.trim();
        // 🔥 불꽃 속성
        if ("파이리".equals(name) || "가디".equals(name) || "칠색조".equals(name)) return "화염방사";
        // 💧 물 속성
        if ("꼬부기".equals(name) || "발챙이".equals(name) || "라프라스".equals(name)) return "하이드로펌프";
        // 🍃 풀 속성
        if ("이상해씨".equals(name) || "뚜벅초".equals(name)) return "솔라빔";
        // ⚡ 전기 속성
        if ("피카츄".equals(name) || "피츄".equals(name) || "뮤츠".equals(name) || "루기아".equals(name)) return "10만볼트";

        return "몸통박치기"; // 일반 속성 기본값
    }

    private double getMatchupMultiplier(String attacker, String defender) {
        String att = attacker.trim();
        String def = defender.trim();

        String attType = getPokemonType(att);
        String defType = getPokemonType(def);

        // 상성 역학 테이블 공식: 물(WATER) -> 불(FIRE) -> 풀(GRASS) -> 물(WATER)
        if ("WATER".equals(attType) && "FIRE".equals(defType)) return 1.5; // 효과가 굉장했다!
        if ("FIRE".equals(attType) && "GRASS".equals(defType)) return 1.5;
        if ("GRASS".equals(attType) && "WATER".equals(defType)) return 1.5;

        if ("FIRE".equals(attType) && "WATER".equals(defType)) return 0.5; // 효과가 별로였다..
        if ("GRASS".equals(attType) && "FIRE".equals(defType)) return 0.5;
        if ("WATER".equals(attType) && "GRASS".equals(defType)) return 0.5;

        return 1.0; // 무상성 1배 타격
    }

    private String getPokemonType(String name) {
        if ("파이리".equals(name) || "가디".equals(name) || "칠색조".equals(name)) return "FIRE";
        if ("꼬부기".equals(name) || "발챙이".equals(name) || "라프라스".equals(name)) return "WATER";
        if ("이상해씨".equals(name) || "뚜벅초".equals(name)) return "GRASS";
        if ("피카츄".equals(name) || "피츄".equals(name)) return "ELECTRIC";

        return "NORMAL";
    }
}