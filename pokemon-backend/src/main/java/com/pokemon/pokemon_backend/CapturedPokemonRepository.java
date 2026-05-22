package com.pokemon.pokemon_backend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface CapturedPokemonRepository extends JpaRepository<CapturedPokemon, Long> {
    // 💡 특정 트레이너가 가진 포켓몬 리스트만 조회하는 쿼리 메서드
    List<CapturedPokemon> findByOwnerId(String ownerId);
}