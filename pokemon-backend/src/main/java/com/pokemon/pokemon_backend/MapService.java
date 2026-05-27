package com.pokemon.pokemon_backend;

import org.springframework.stereotype.Service;
import java.util.Random;

@Service
public class MapService {

    private final int[][] townMap = {
            {0, 0, 0, 1, 1},
            {1, 1, 0, 1, 0},
            {2, 2, 0, 3, 0},
            {2, 1, 1, 1, 0},
            {0, 0, 0, 1, 0}
    };

    private final int[][] fieldMap = {
            {0, 0, 0, 0, 0},
            {0, 1, 1, 1, 2},
            {0, 0, 0, 2, 2},
            {0, 1, 0, 1, 0},
            {1, 1, 0, 1, 1}
    };

    private final int TILE_SIZE = 80;
    private final Random random = new Random();

    // 🦖 [도감 25종 대확장 및 4단계 가중치 풀 격리 구성]

    // 1. 🍃 일반 등급 (출현 확률: 75.0%) - 10마리
    private final String[] commonPool = {
            "구구", "꼬렛", "캐터피", "아보", "피츄",
            "모래두지", "뚜벅초", "발챙이", "디그다", "주뱃"
    };

    // 2. ✨ 레어 등급 (출현 확률: 21.5%) - 8마리
    private final String[] rarePool = {
            "피카츄", "파이리", "꼬부기", "이상해씨",
            "이브이", "가디", "고스트", "롱스톤"
    };

    // 3. 🌌 환상 등급 (출현 확률: 3.4%) - 4마리
    private final String[] mysticPool = {
            "미뇽", "잠만보", "켄타로스", "라프라스"
    };

    // 4. 👑 초전설 등급 (출현 확률: 0.1% - 만분율 10) - 3마리
    private final String[] legendaryPool = {
            "뮤츠", "루기아", "칠색조"
    };

    public boolean checkCollision(int nextX, int nextY, String currentDbMap, String safeClientMap) {
        if (!currentDbMap.equals(safeClientMap)) return true;

        int tileX = (nextX + 32) / TILE_SIZE;
        int tileY = (nextY + 32) / TILE_SIZE;

        if (tileX < 0 || tileX >= 5 || tileY < 0 || tileY >= 5) return false;
        int[][] targetMapArr = "field".equals(safeClientMap) ? fieldMap : townMap;
        return targetMapArr[tileY][tileX] != 1;
    }

    public String checkEncounter(int currentX, int currentY, String mapName) {
        int tileX = (currentX + 32) / TILE_SIZE;
        int tileY = (currentY + 32) / TILE_SIZE;

        if (tileX < 0 || tileX >= 5 || tileY < 0 || tileY >= 5) return null;
        int[][] targetMapArr = "field".equalsIgnoreCase(mapName) ? fieldMap : townMap;

        if (targetMapArr[tileY][tileX] == 2) {
            // 풀숲 주사위 (15% 확률로 조우)
            if (random.nextInt(100) < 15) {

                // 🎲 등급 결정 고정밀 만분율 주사위 (0 ~ 9999)
                int gradeDice = random.nextInt(10000);

                // [초전설] 0.1% 확률 (0 ~ 9)
                if (gradeDice < 10) {
                    return legendaryPool[random.nextInt(legendaryPool.length)];
                }
                // [환상] 3.4% 확률 (10 ~ 349)
                else if (gradeDice < 350) {
                    return mysticPool[random.nextInt(mysticPool.length)];
                }
                // [레어] 21.5% 확률 (350 ~ 2499)
                else if (gradeDice < 2500) {
                    return rarePool[random.nextInt(rarePool.length)];
                }
                // [일반] 나머지 75.0% 확률 (2500 ~ 9999)
                else {
                    return commonPool[random.nextInt(commonPool.length)];
                }
            }
        }
        return null;
    }
}