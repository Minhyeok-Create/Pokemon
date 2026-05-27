package com.pokemon.pokemon_backend;

import org.springframework.stereotype.Service;
import java.util.Random;

@Service
public class MapService {

    // 🗺️ 맵 데이터 격리 관리 (0: 잔디, 1: 갈색벽, 2: 🌾포켓몬 풀숲, 3: 🏥포켓몬센터)
    private final int[][] townMap = {
            {0, 0, 0, 1, 1},
            {1, 1, 0, 1, 0},
            {2, 2, 0, 3, 0}, // 💡 프론트엔드와 싱크를 맞춰 3번 타일 영역 유지
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

    // 🦖 [도감 대확장]: 등급별 확정 출현 풀 분리 빌드업
    private final String[] commonPool = {"피츄", "구구", "꼬렛", "캐터피", "아보"};
    private final String[] rarePool = {"피카츄", "파이리", "꼬부기", "이상해씨"};
    private final String[] legendaryPool = {"뮤츠", "루기아"}; // 🌟 극악 확률의 전설의 포켓몬 추가!

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

        // 1(벽)만 아니면 무조건 지나갈 수 있습니다.
        return targetMapArr[tileY][tileX] != 1;
    }

    /**
     * 🌾 [가중치 확률 엔진 대개조]:
     * 풀숲을 밟았을 때 15% 조우 확률을 뚫으면, 다시 만분율(1/10000) 주사위를 던져
     * 전설 등급(0.1%), 희귀 등급(14.9%), 일반 등급(85%)을 정교하게 가라앉힙니다.
     */
    public String checkEncounter(int currentX, int currentY, String mapName) {
        int tileX = (currentX + 32) / TILE_SIZE;
        int tileY = (currentY + 32) / TILE_SIZE;

        if (tileX < 0 || tileX >= 5 || tileY < 0 || tileY >= 5) return null;

        int[][] targetMapArr = "field".equalsIgnoreCase(mapName) ? fieldMap : townMap;

        // 내 발밑이 2(풀숲)라면?
        if (targetMapArr[tileY][tileX] == 2) {

            // 🎲 주사위 1: 풀숲 조우 확률 자체를 기존 10%에서 15%로 살짝 올려 손맛을 보강합니다! (100 중 15 미만)
            if (random.nextInt(100) < 15) {

                // 🎲 주사위 2: 등급을 가를 고정밀 만분율(0 ~ 9999) 주사위를 장착합니다.
                int gradeDice = random.nextInt(10000);

                // 1. 🌟 [전설의 등급] 정확히 0.1% 확률 가드 (0부터 9999 중 딱 10개만 당첨, 만분율 10)
                if (gradeDice < 10) {
                    int idx = random.nextInt(legendaryPool.length);
                    System.out.println("👑 [!!!전설 출현!!!] 만분율 " + gradeDice + " 돌파! 야생의 " + legendaryPool[idx] + " 등장!");
                    return legendaryPool[idx];
                }

                // 2. ✨ [레어/희귀 등급] 대략 14.9% 확률 가드 (10 이상 1500 미만, 스타팅몬 풀)
                else if (gradeDice < 1500) {
                    int idx = random.nextInt(rarePool.length);
                    return rarePool[idx];
                }

                // 3. 🍃 [일반 등급] 나머지 무난한 85% 확률 (1500 이상)
                else {
                    int idx = random.nextInt(commonPool.length);
                    return commonPool[idx];
                }
            }
        }
        return null;
    }
}