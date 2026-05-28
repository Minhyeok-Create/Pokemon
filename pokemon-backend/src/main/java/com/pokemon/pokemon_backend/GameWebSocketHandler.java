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

    // ⚔️ [PvP 신설 메모리 주머니]: 실시간 유저 대인전 세션 및 방 점유 추적용 고속 보관소
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

            // 🏃‍♂️ [C] 캐릭터 이동 요청
            else if ("MOVE".equals(type)) {
                if (username == null) return;
                // 🚨 PvP 대전 중에는 필드 조작 이동 불가 하드 가드 작동
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

            // 🥊 [D] 전투 커맨드: 공격하기 (🚨 야생전과 PvP 멀티플레이 정밀 분기 처리)
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

                    int dmg = random.nextInt(5) + 4;
                    battle.setEnemyCurrentHp(Math.max(0, battle.getEnemyCurrentHp() - dmg));
                    res.put("enemyHp", battle.getEnemyCurrentHp());
                    res.put("myPokemonName", activePokeName);

                    // 🏆 [야생 포켓몬 격파 -> 경험치 및 레벨업 정산 시퀀스]
                    if (battle.getEnemyCurrentHp() <= 0) {
                        StringBuilder logBuilder = new StringBuilder();
                        logBuilder.append("💥 ").append(activePokeName).append("의 몸통박치기 공격! (").append(dmg).append(" 데미지!)\n\n");
                        logBuilder.append("🎉 야생의 ").append(battle.getEnemyName()).append("이(가) 쓰러졌다!\n");

                        // 1. 경험치 보상 계산: 야생몬 레벨 * 5
                        int gainedExp = battle.getEnemyLevel() * 5;
                        myActivePoke.setExp(myActivePoke.getExp() + gainedExp);
                        logBuilder.append("✨ ").append(activePokeName).append("은(는) ").append(gainedExp).append(" XP의 경험치를 획득했다! ");

                        // 2. 루프형 레벨업 체크 (경험치가 요구치를 초과하면 연쇄 레벨업 가능)
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

                        // 3. 변동된 능력치 DB에 즉시 영구 저장
                        capturedPokemonRepository.saveAndFlush(myActivePoke);

                        res.put("log", logBuilder.toString());
                        res.put("battleEnded", true);
                        activeBattles.remove(username);

                        // 전투 종료 후 리셋을 대비해 최신 가방 상태를 재동기화해서 보냅니다.
                        myPokes = capturedPokemonRepository.findByOwnerId(username);
                        Map<String, Object> bagResponse = new HashMap<>();
                        bagResponse.put("type", "BAG_RESULT");
                        bagResponse.put("list", myPokes);
                        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(bagResponse)));
                    }
                    // 🔄 야생 포켓몬 반격 타임
                    else {
                        int counterDmg = random.nextInt(3) + 1;
                        int nextMyHp = Math.max(0, myActivePoke.getCurrentHp() - counterDmg);

                        myActivePoke.setCurrentHp(nextMyHp);
                        capturedPokemonRepository.saveAndFlush(myActivePoke);

                        res.put("myPokemonHp", nextMyHp);
                        res.put("myPokemonMaxHp", myActivePoke.getMaxHp());

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
                            String[] skills = {"전광석화", "몸통박치기", "할퀴기"};
                            res.put("log", "💥 " + activePokeName + "의 " + skills[random.nextInt(skills.length)] + "! (" + dmg + " 데미지!)\n🏃‍♂️ 야생의 " + battle.getEnemyName() + "의 반격! (" + counterDmg + " 피해!)");
                            res.put("battleEnded", false);
                        }
                    }
                }
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(res)));
            }

            // ⚔️ [PvP 신설 수신부 1]: 타 플레이어 정밀 조준 결투 신청 패킷 접수처
            else if ("PVP_INVITE".equals(type)) {
                if (username == null) return;
                String targetUserId = (String) requestData.get("targetUserId");
                Map<String, Object> res = new HashMap<>();

                // 예외 가드: 오프라인 수색 및 중복 배틀 상태 가둬두기
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

                // 고유 매칭 전용 룸 등록 계약
                String roomId = "ROOM_" + username + "_VS_" + targetUserId;
                PvPState pvpState = new PvPState(roomId, username, session, targetUserId, targetSession);
                activePvPMatches.put(roomId, pvpState);
                userToRoomMap.put(username, roomId);
                userToRoomMap.put(targetUserId, roomId);

                // 📡 피신청자에게 결투 팝업 호출 포워딩 패킷 사출
                Map<String, Object> inviteForward = new HashMap<>();
                inviteForward.put("type", "PVP_INVITE_FORWARD");
                inviteForward.put("senderUserId", username);
                inviteForward.put("roomId", roomId);
                targetSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(inviteForward)));

                // 신청자에게 대기방 영수증 사출
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
                    pvp.setStatus("BATTLE");

                    java.util.List<CapturedPokemon> p1Bag = capturedPokemonRepository.findByOwnerId(pvp.getP1Username());
                    java.util.List<CapturedPokemon> p2Bag = capturedPokemonRepository.findByOwnerId(pvp.getP2Username());

                    if (p1Bag.isEmpty() || p2Bag.isEmpty()) {
                        userToRoomMap.remove(pvp.getP1Username()); userToRoomMap.remove(pvp.getP2Username());
                        activePvPMatches.remove(roomId);
                        return;
                    }

                    // 양측 최전선 1선 포켓몬 배치 완료
                    pvp.setP1ActivePoke(p1Bag.get(0));
                    pvp.setP2ActivePoke(p2Bag.get(0));

                    // 📡 전투 화면 강제 소환 비동기 패킷 투사
                    sendPvPStartPacket(pvp);
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

            // 🏃‍♂️ [F] 도망치기 (🚨 PvP 도중 기권 탈출 핸들링 통합 수리)
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

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessions.remove(session.getId());
        String username = (String) session.getAttributes().get("username");

        if (username != null) {
            // 🚨 [PvP 강제 탈주 리스크 방어]: 전투 도중 브라우저 끄거나 탈주 시 부전승 통보 처리
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

        // ⚔️ 2. 리얼타임 난수 공방전 연산 (50% 스피드 확률 선제 타격권 부여)
        int p1Dmg = random.nextInt(5) + 4; int p2Dmg = random.nextInt(5) + 4;
        boolean p1First = random.nextBoolean();

        if (p1First) {
            pvp.getP2ActivePoke().setCurrentHp(Math.max(0, pvp.getP2ActivePoke().getCurrentHp() - p1Dmg));
            p1Log.append("⚔️ 나의 ").append(pvp.getP1ActivePoke().getPokemonName()).append("의 몸통박치기 성공! (").append(p1Dmg).append(" 피해!)\n");
            p2Log.append("💥 상대 ").append(pvp.getP1ActivePoke().getPokemonName()).append("의 선제 타격 기습! (-").append(p1Dmg).append(" HP)\n");
            if (pvp.getP2ActivePoke().getCurrentHp() > 0) {
                pvp.getP1ActivePoke().setCurrentHp(Math.max(0, pvp.getP1ActivePoke().getCurrentHp() - p2Dmg));
                p1Log.append("💥 상대 ").append(pvp.getP2ActivePoke().getPokemonName()).append("의 분노 섞인 반격! (-").append(p2Dmg).append(" HP)\n");
                p2Log.append("⚔️ 나의 ").append(pvp.getP2ActivePoke().getPokemonName()).append("의 짜릿한 복수 응징! (").append(p2Dmg).append(" 피해!)\n");
            } else { p2Log.append("💀 포켓몬이 기절하여 반격할 타이밍을 유실했습니다.\n"); }
        } else {
            pvp.getP1ActivePoke().setCurrentHp(Math.max(0, pvp.getP1ActivePoke().getCurrentHp() - p2Dmg));
            p1Log.append("💥 상대 ").append(pvp.getP2ActivePoke().getPokemonName()).append("의 선제 타격 기습! (-").append(p2Dmg).append(" HP)\n");
            p2Log.append("⚔️ 나의 ").append(pvp.getP2ActivePoke().getPokemonName()).append("의 몸통박치기 성공! (").append(p2Dmg).append(" 피해!)\n");
            if (pvp.getP1ActivePoke().getCurrentHp() > 0) {
                pvp.getP2ActivePoke().setCurrentHp(Math.max(0, pvp.getP2ActivePoke().getCurrentHp() - p1Dmg));
                p1Log.append("⚔️ 나의 ").append(pvp.getP1ActivePoke().getPokemonName()).append("의 짜릿한 복수 응징! (").append(p1Dmg).append(" 피해!)\n");
                p2Log.append("💥 상대 ").append(pvp.getP1ActivePoke().getPokemonName()).append("의 분노 섞인 반격! (-").append(p1Dmg).append(" HP)\n");
            } else { p1Log.append("💀 포켓몬이 기절하여 반격할 타이밍을 유실했습니다.\n"); }
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
                p1Log.append("\n💀 무승부! 양측 포켓몬이 동시에 쓰러져 승負가 나지 않았습니다.");
                p2Log.append("\n💀 무승부! 양측 포켓몬이 동시에 쓰러져 승負가 나지 않았습니다.");
            } else if (p1Fainted) {
                p1Log.append("\n💀 내 포켓몬이 쓰러졌습니다.. 대인전 PvP 패배.");
                p2Log.append("\n🏆 축하합니다! 상대를 기절시키고 PvP 멀티 아레나에서 대승리했습니다!");
            } else {
                p1Log.append("\n🏆 축하합니다! 상대를 기절시키고 PvP 멀티 아레나에서 대승리했습니다!");
                p2Log.append("\n💀 내 포켓몬이 쓰러졌습니다.. 대인전 PvP 패배.");
            }
            p1Res.put("log", p1Log.toString()); p2Res.put("log", p2Log.toString());
            sendPvPResponseAndClose(pvp, p1Res, p2Res);
        } else {
            p1Res.put("battleEnded", false); p2Res.put("battleEnded", false);
            p1Log.append("\n 다음 명령을 입력하세요."); p2Log.append("\n 다음 명령을 입력하세요.");
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
}