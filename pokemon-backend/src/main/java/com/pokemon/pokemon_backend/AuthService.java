package com.pokemon.pokemon_backend;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class AuthService {

    @Autowired
    private PlayerRepository playerRepository;

    @Autowired
    private CapturedPokemonRepository capturedPokemonRepository; // 👈 리포지토리 주입

    public boolean isUsernameTaken(String username) {
        return playerRepository.existsById(username);
    }

    @Transactional
    public void registerNewPlayer(String username, String password) {
        Player newPlayer = new Player();
        newPlayer.setId(username);
        newPlayer.setPassword(password);
        newPlayer.setX(180);
        newPlayer.setY(130);
        newPlayer.setMap("town");
        playerRepository.save(newPlayer);

        // 🎁 [스타팅 포켓몬 선물] 신규 트레이너에게 레벨 5짜리 피카츄 한 마리를 가방에 넣어줍니다!
        CapturedPokemon startingPokemon = new CapturedPokemon();
        startingPokemon.setOwnerId(username);
        startingPokemon.setPokemonName("피카츄");
        startingPokemon.setLevel(5);
        startingPokemon.setMaxHp(20);
        startingPokemon.setCurrentHp(20);
        capturedPokemonRepository.save(startingPokemon);
    }

    public Player loadPlayer(String username) {
        return playerRepository.findById(username).orElse(null);
    }

    // 💡 [추가] 유저의 가방 데이터를 긁어오는 비즈니스 메서드
    public List<CapturedPokemon> getMyPokemons(String username) {
        return capturedPokemonRepository.findByOwnerId(username);
    }

    @Transactional
    public void updatePlayerPosition(String username, int x, int y, String map) {
        playerRepository.findById(username).ifPresent(p -> {
            p.setX(x);
            p.setY(y);
            p.setMap(map);
            playerRepository.saveAndFlush(p);
        });
    }
}