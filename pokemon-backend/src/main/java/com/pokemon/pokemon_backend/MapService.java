package com.pokemon.pokemon_backend;

import org.springframework.stereotype.Service;
import java.util.Random;

@Service
public class MapService {

    // 🗺️ 맵 데이터 격리 관리 (0: 잔디, 1: 갈색벽, 2: 🌾포켓몬 풀숲 추가)
    private final int[][] townMap = {
            {0, 0, 0, 1, 1},
            {1, 1, 0, 1, 0},
            {2, 2, 0, 0, 0}, // 👈 3번째 줄 앞부분에 풀숲 배치
            {2, 1, 1, 1, 0}, // 👈 4번째 줄에도 풀숲 연계
            {0, 0, 0, 1, 0}
    };

    private final int[][] fieldMap = {
            {0, 0, 0, 0, 0},
            {0, 1, 1, 1, 2}, // 👈 우측 상단 풀숲 구역
            {0, 0, 0, 2, 2}, // 👈 들판에도 풀숲 심어주기
            {0, 1, 0, 1, 0},
            {1, 1, 0, 1, 1}
    };

    private final int TILE_SIZE = 80;
    private final Random random = new Random();

    // 🦖 등장 가능한 야생 포켓몬 도감 리스트
    private final String[] wildPokemonPool = {"피츄", "구구", "꼬렛", "캐터피", "아보"};

    public boolean checkCollision(int nextX, int nextY, String currentDbMap, String safeClientMap) {
        if (!currentDbMap.equals(safeClientMap)) {
            return true;
        }

        int tileX = (nextX + 32) / TILE_SIZE;
        int tileY = (nextY + 32) / TILE_SIZE;

        if (tileX < 0 || tileX >= 5 || tileY < 0 || tileY >= 5) {
            return false;
        }

        int[][] targetMapArr = "field".equals(safeClientMap) ? fieldMap : townMap;

        // 💡 [수정] 1(벽)만 아니면 0(잔디)이든 2(풀숲)이든 무조건 지나갈 수 있습니다.
        return targetMapArr[tileY][tileX] != 1;
    }

    /**
     * 🌾 유저가 이동한 최종 타일이 풀숲(2)인지 체크하고,
     * 맞다면 10% 확률로 등장한 포켓몬 이름을 리턴합니다. (안 나오면 null)
     */
    public String checkEncounter(int currentX, int currentY, String mapName) {
        int tileX = (currentX + 32) / TILE_SIZE;
        int tileY = (currentY + 32) / TILE_SIZE;

        if (tileX < 0 || tileX >= 5 || tileY < 0 || tileY >= 5) return null;

        int[][] targetMapArr = "field".equalsIgnoreCase(mapName) ? fieldMap : townMap;

        // 내 발밑이 2(풀숲)라면?
        if (targetMapArr[tileY][tileX] == 2) {
            // 🎲 10% 확률 주사위 굴리기 (0부터 9까지 중 0이 나올 때)
            if (random.nextInt(10) == 0) {
                // 포켓몬 풀에서 랜덤으로 한 마리 선정
                int randomIndex = random.nextInt(wildPokemonPool.length);
                return wildPokemonPool[randomIndex];
            }
        }
        return null;
    }
}