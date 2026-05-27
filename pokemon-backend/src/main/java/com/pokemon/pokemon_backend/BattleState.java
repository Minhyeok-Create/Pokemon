package com.pokemon.pokemon_backend;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BattleState {
    private String trainerId;
    private String enemyName;
    private int enemyLevel;
    private int enemyMaxHp;
    private int enemyCurrentHp;
    private Long playerActivePokemonUniqueId;
    public BattleState(String trainerId, String enemyName, int enemyLevel, int enemyHp) {
        this.trainerId = trainerId;
        this.enemyName = enemyName;
        this.enemyLevel = enemyLevel;
        this.enemyMaxHp = enemyHp;
        this.enemyCurrentHp = enemyHp;
        this.playerActivePokemonUniqueId = null;
    }

}