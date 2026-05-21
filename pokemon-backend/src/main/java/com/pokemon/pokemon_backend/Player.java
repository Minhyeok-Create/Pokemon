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
    private String id;
    private String password;
    private int x;
    private int y;

}
