// ==========================================
// [MODULE 1] CONSTANTS & CONFIGURATIONS
// ==========================================
const TILE_SIZE = 80;
const SPEED = 8;
const CHAR_SIZE = 48;

// 🗺️ 3대 대륙 지형 격자 데이터 레이아웃
const MAPS = {
    town: [
        [0, 0, 0, 1, 1],
        [1, 1, 0, 1, 0],
        [2, 2, 0, 3, 0], 
        [2, 1, 1, 1, 0], 
        [0, 0, 0, 1, 0]
    ],
    field: [
        [0, 0, 0, 0, 0],
        [0, 1, 1, 1, 2], 
        [0, 0, 0, 2, 2], 
        [0, 1, 0, 1, 0],
        [1, 1, 0, 1, 1]
    ],
    dungeon: [
        [1, 1, 0, 1, 1],
        [1, 2, 2, 2, 1], 
        [0, 2, 1, 2, 0], 
        [1, 2, 2, 2, 1],
        [1, 1, 1, 1, 1]
    ]
};

const IS_LOCALHOST = window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1';
const SERVER_URL = IS_LOCALHOST
    ? 'ws://localhost:8080/game'
    : 'wss://pokemon-backend-o9nh.onrender.com/game';

// 🦖 야생 몬스터 스킨 풀 (PokeAPI)
const POKEMON_IMAGE_POOL = {
    // 1세대 기본 및 흔한 포켓몬
    "캐터피": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/10.png",
    "구구": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/16.png",
    "꼬렛": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/19.png",
    "주뱃": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/41.png",
    "뚜벅초": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/43.png",
    "디그다": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/50.png",
    "발챙이": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/60.png",
    "아보": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/23.png",
    "모래두지": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/27.png",
    "가디": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/58.png",
    "고스트": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/93.png",
    "롱스톤": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/95.png",
    "야도란": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/80.png",
    
    // 1세대 스타팅 및 최종 진화 / 네임드종
    "이상해씨": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/1.png",
    "이상해꽃": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/3.png",
    "파이리": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/4.png",
    "리자몽": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/6.png",
    "꼬부기": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/7.png",
    "거북왕": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/9.png",
    "피카츄": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/25.png",
    "이브이": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/133.png",
    "라프라스": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/131.png",
    "켄타로스": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/128.png",
    "잠만보": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/143.png",
    "미뇽": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/147.png",
    "망나뇽": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/149.png",

    // 2세대 스타팅 및 인기종 / 진화형
    "피츄": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/172.png",
    "치코리타": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/152.png",
    "브케인": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/155.png",
    "리아코": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/158.png",
    "전룡": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/181.png",
    "토게피": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/175.png",
    "마기라스": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/248.png",

    // 전설 및 초전설 3대장 계열
    "앤테이": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/244.png",
    "라이코": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/243.png",
    "스이쿤": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/245.png",
    "썬더": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/145.png",
    "뮤": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/151.png",
    "세레비": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/251.png",
    "뮤츠": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/150.png",
    "루기아": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/249.png",
    "칠색조": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/250.png"
};

// 🎒 [대규모 확장] 내 아군 몬스터 뒷모습 스킨 풀 (PokeAPI 정식 매핑 - 38종)
const POKEMON_BACK_IMAGE_POOL = {
    // 1세대 기본 및 흔한 포켓몬
    "캐터피": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/10.png",
    "구구": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/16.png",
    "꼬렛": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/19.png",
    "주뱃": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/41.png",
    "뚜벅초": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/43.png",
    "디그다": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/50.png",
    "발챙이": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/60.png",
    "아보": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/23.png",
    "모래두지": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/27.png",
    "가디": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/58.png",
    "고스트": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/93.png",
    "롱스톤": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/95.png",
    "야도란": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/80.png",
    
    // 1세대 스타팅 및 최종 진화 / 네임드종
    "이상해씨": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/1.png",
    "이상해꽃": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/3.png",
    "파이리": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/4.png",
    "리자몽": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/6.png",
    "꼬부기": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/7.png",
    "거북왕": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/9.png",
    "피카츄": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/25.png",
    "이브이": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/133.png",
    "라프라스": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/131.png",
    "켄타로스": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/128.png",
    "잠만보": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/143.png",
    "미뇽": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/147.png",
    "망나뇽": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/149.png",

    // 2세대 스타팅 및 인기종 / 진화형
    "피츄": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/172.png",
    "치코리타": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/152.png",
    "브케인": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/155.png",
    "리아코": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/158.png",
    "전룡": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/181.png",
    "토게피": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/175.png",
    "마기라스": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/248.png",

    // 전설 및 초전설 3대장 계열
    "앤테이": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/244.png",
    "라이코": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/243.png",
    "스이쿤": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/245.png",
    "썬더": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/145.png",
    "뮤": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/151.png",
    "세레비": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/251.png",
    "뮤츠": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/150.png",
    "루기아": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/249.png",
    "칠색조": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/250.png"
};