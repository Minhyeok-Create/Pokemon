package com.pokemon.pokemon_backend;

import org.springframework.web.socket.WebSocketSession;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PvPState {
    private String roomId;
    private String p1Username; // 도전장을 보낸 트레이너 (신청자)
    private String p2Username; // 도전장을 받은 트레이너 (피신청자)

    private WebSocketSession p1Session;
    private WebSocketSession p2Session;

    private String status = "WAITING"; // WAITING(수락 대기중), BATTLE(전투중)

    private String p1Action = null;
    private String p2Action = null;

    private CapturedPokemon p1ActivePoke;
    private CapturedPokemon p2ActivePoke;

    private int round = 1;

    public PvPState(String roomId, String p1Username, WebSocketSession p1Session, String p2Username, WebSocketSession p2Session) {
        this.roomId = roomId;
        this.p1Username = p1Username;
        this.p1Session = p1Session;
        this.p2Username = p2Username;
        this.p2Session = p2Session;
    }
}