package com.pokemon.pokemon_backend;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "CAPTURED_POKEMONS")
@Getter
@Setter
public class CapturedPokemon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long uniqueId;       // 데이터베이스 자동 순번 고유키

    private String ownerId;      // 소유한 트레이너의 아이디 (Player의 id와 연동)
    private String pokemonName;  // 포켓몬 이름 (예: 피카츄, 구구)
    private int level;           // 포켓몬 레벨
    private int maxHp;           // 최대 체력
    private int currentHp;       // 현재 체력
    private int exp = 0;
}