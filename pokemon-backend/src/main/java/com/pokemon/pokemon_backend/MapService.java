package com.pokemon.pokemon_backend;

import org.springframework.stereotype.Service;
import java.util.Random;

@Service
public class MapService {

    // 🗺️ 1. 마을 맵 (5x5)
    private final int[][] townMap = {
            {0, 0, 0, 1, 1},
            {1, 1, 0, 1, 0},
            {2, 2, 0, 3, 0},
            {2, 1, 1, 1, 0},
            {0, 0, 0, 1, 0}
    };

    // 🗺️ 2. 들판 맵 (5x5)
    private final int[][] fieldMap = {
            {0, 0, 0, 0, 0},
            {0, 1, 1, 1, 2},
            {0, 0, 0, 2, 2},
            {0, 1, 0, 1, 0},
            {1, 1, 0, 1, 1}
    };

    // 🗺️ 3. [신설] 용의 굴 던전 맵 (5x5)
    private final int[][] dungeonMap = {
            {1, 1, 0, 1, 1},
            {1, 2, 2, 2, 1}, // 👈 던전 마그마 풀숲 배치
            {0, 2, 1, 2, 0},
            {1, 2, 2, 2, 1},
            {1, 1, 1, 1, 1}
    };

    private final int TILE_SIZE = 80;
    private final Random random = new Random();

    // 등급별 세부 몬스터 도감 리스트
    private final String[] commonPool = {"구구", "꼬렛", "캐터피", "아보", "피츄", "모래두지", "뚜벅초", "발챙이", "디그다", "주뱃"};
    private final String[] rarePool = {"피카츄", "파이리", "꼬부기", "이상해씨", "이브이", "가디", "고스트", "롱스톤"};
    private final String[] mysticPool = {"미뇽", "잠만보", "켄타로스", "라프라스"};
    private final String[] legendaryPool = {"뮤츠", "루기아", "칠색조"};

    public boolean checkCollision(int nextX, int nextY, String currentDbMap, String safeClientMap) {
        if (!currentDbMap.equals(safeClientMap)) return true;

        int tileX = (nextX + 32) / TILE_SIZE;
        int tileY = (nextY + 32) / TILE_SIZE;

        if (tileX < 0 || tileX >= 5 || tileY < 0 || tileY >= 5) return false;

        // 🛠️ 대조할 맵 배열 스위칭
        int[][] targetMapArr = townMap;
        if ("field".equals(safeClientMap)) targetMapArr = fieldMap;
        else if ("dungeon".equals(safeClientMap)) targetMapArr = dungeonMap;

        return targetMapArr[tileY][tileX] != 1;
    }

    /**
     * [서식지 격리형 가중치]:
     * 어떤 맵에서 수풀을 밟았냐에 따라 출현하는 몬스터의 등급 한계 제어
     */
    public String checkEncounter(int currentX, int currentY, String mapName) {
        int tileX = (currentX + 32) / TILE_SIZE;
        int tileY = (currentY + 32) / TILE_SIZE;

        if (tileX < 0 || tileX >= 5 || tileY < 0 || tileY >= 5) return null;

        int[][] targetMapArr = townMap;
        if ("field".equalsIgnoreCase(mapName)) targetMapArr = fieldMap;
        else if ("dungeon".equalsIgnoreCase(mapName)) targetMapArr = dungeonMap;

        // 발밑이 풀숲(2)일 때
        if (targetMapArr[tileY][tileX] == 2) {
            if (random.nextInt(100) < 6) { // 15% 조우 주사위

                int gradeDice = random.nextInt(10000); // 만분율 주사위

                // [용의 굴 던전] 서식지: 레어(75%), 환상(20%), 초전설(5% - 0.1%에서 대폭 상향!)
                if ("dungeon".equalsIgnoreCase(mapName)) {
                    if (gradeDice < 500) { // 5% 확률로 초전설 보스 출현! (500/10000)
                        return legendaryPool[random.nextInt(legendaryPool.length)];
                    } else if (gradeDice < 2500) { // 20% 확률로 환상 등급 출현
                        return mysticPool[random.nextInt(mysticPool.length)];
                    } else { // 75% 확률로 강력한 레어 등급 진화체들 출현
                        return rarePool[random.nextInt(rarePool.length)];
                    }
                }

                // [들판 맵] 서식지: 일반(80%), 레어(20%)
                else if ("field".equalsIgnoreCase(mapName)) {
                    if (gradeDice < 2000) {
                        return rarePool[random.nextInt(rarePool.length)];
                    } else {
                        return commonPool[random.nextInt(commonPool.length)];
                    }
                }

                // [마을 맵] 서식지: 일반 등급
                else {
                    return commonPool[random.nextInt(commonPool.length)];
                }
            }
        }
        return null;
    }
}