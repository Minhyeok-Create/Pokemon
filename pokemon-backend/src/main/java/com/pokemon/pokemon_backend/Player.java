package com.pokemon.pokemon_backend;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "PLAYERS")
@Getter
@Setter
public class Player {
    @Id
    private String id;       // 트레이너 로그인 아이디 (Primary Key)
    private String password; // 트레이너 비밀번호
    private int x;           // 캐릭터 실시간 X 좌표
    private int y;           // 캐릭터 실시간 Y 좌표

    private String map;
}