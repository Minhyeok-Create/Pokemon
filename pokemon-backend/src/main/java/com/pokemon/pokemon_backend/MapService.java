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

    // 🗺️ 3. 용의 굴 던전 맵 (5x5)
    private final int[][] dungeonMap = {
            {1, 1, 0, 1, 1},
            {1, 2, 2, 2, 1}, // 👈 던전 마그마 풀숲 배치
            {0, 2, 1, 2, 0},
            {1, 2, 2, 2, 1},
            {1, 1, 1, 1, 1}
    };

    private final int TILE_SIZE = 80;
    private final Random random = new Random();

    // 🦖 [38종 도감 싱크 패치]: 등급별 출현 몬스터 리스트 전면 확장
    // 1) Common (마을 주변, 흔함): 2세대의 토게피 포함 흔둥이들 대거 합류
    private final String[] commonPool = {
            "구구", "꼬렛", "캐터피", "아보", "피츄", "모래두지", "뚜벅초", "발챙이", "디그다", "주뱃", "토게피"
    };

    // 2) Rare (들판 수풀, 1~2세대 스타팅 및 네임드): 브케인, 리아코, 치코리타, 전룡, 야도란 도킹
    private final String[] rarePool = {
            "피카츄", "파이리", "꼬부기", "이상해씨", "이브이", "가디", "고스트", "롱스톤",
            "브케인", "리아코", "치코리타", "전룡", "야도란"
    };

    // 3) Mystic (던전 서식, 최종 진화형 및 헤비급): 리자몽, 거북왕, 이상해꽃, 망나뇽, 마기라스, 잠만보 등 출격
    private final String[] mysticPool = {
            "미뇽", "잠만보", "켄타로스", "라프라스", "리자몽", "거북왕", "이상해꽃", "망나뇽", "마기라스"
    };

    // 4) Legendary (던전 5% 확률 극악 보스, 초전설 & 준전설 개 3대장): 앤테이, 라이코, 스이쿤, 썬더, 뮤, 세레비 하이엔드 배치
    private final String[] legendaryPool = {
            "뮤츠", "루기아", "칠색조", "앤테이", "라이코", "스이쿤", "썬더", "뮤", "세레비"
    };

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
     * [서식지 격리형 가중치 엔진]
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
            if (random.nextInt(100) < 6) { // 15% 조우 주사위 가동

                int gradeDice = random.nextInt(10000); // 만분율 주사위

                // [용의 굴 던전] 서식지: 레어(75%), 환상/최종진화(20%), 초전설/준전설 보스(5%)
                if ("dungeon".equalsIgnoreCase(mapName)) {
                    if (gradeDice < 400) { // 5% 확률로 레전더리 풀 출현! (500/10000)
                        return legendaryPool[random.nextInt(legendaryPool.length)];
                    } else if (gradeDice < 2000) { // 20% 확률로 미스틱 등급 출현
                        return mysticPool[random.nextInt(mysticPool.length)];
                    } else { // 75% 확률로 레어 등급 스타팅 및 네임드 출현
                        return rarePool[random.nextInt(rarePool.length)];
                    }
                }

                // [들판 맵] 서식지: 일반(80%), 레어/스타팅(20%)
                else if ("field".equalsIgnoreCase(mapName)) {
                    if (gradeDice < 50) {
                        // ① 0.5% 확률로 극악의 로또 전설몬 강림! (0~49)
                        System.out.println("🚨 [필드 기적!] 들판 풀숲에서 0.5% 전설몬 조우!");
                        return legendaryPool[random.nextInt(legendaryPool.length)];
                    } else if (gradeDice < 500) {
                        return mysticPool[random.nextInt(mysticPool.length)];
                    } else if (gradeDice < 2000) {
                        // ② 19.5% 확률로 기존 레어/스타팅 몹 출현 (50~1999)
                        return rarePool[random.nextInt(rarePool.length)];
                    } else {
                        // ③ 나머지 80% 확률로 흔둥이 일반몬 출현 (2000~9999)
                        return commonPool[random.nextInt(commonPool.length)];
                    }
                }

                // [마을 맵] 서식지: 일반 등급  출현
                else {
                    return commonPool[random.nextInt(commonPool.length)];
                }
            }
        }
        return null;
    }
}