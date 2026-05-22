// ==========================================
// 1. CONSTANTS & CONFIGURATIONS
// ==========================================
const TILE_SIZE = 80;
const SPEED = 8;
const CHAR_SIZE = 64;

// 🗺️ 맵 데이터 최신화 (0: 잔디, 1: 벽, 2: 🌾풀숲 타일)
const MAPS = {
    town: [
        [0, 0, 0, 1, 1],
        [1, 1, 0, 1, 0],
        [2, 2, 0, 0, 0], // 👈 2번 풀숲 심기
        [2, 1, 1, 1, 0], // 👈 2번 풀숲 심기
        [0, 0, 0, 1, 0]
    ],
    field: [
        [0, 0, 0, 0, 0],
        [0, 1, 1, 1, 2], // 👈 우측 끝 풀숲 구역
        [0, 0, 0, 2, 2], // 👈 2번 풀숲 심기
        [0, 1, 0, 1, 0],
        [1, 1, 0, 1, 1]
    ]
};

const IS_LOCALHOST = window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1';
const SERVER_URL = IS_LOCALHOST
    ? 'ws://localhost:8080/game'
    : 'wss://pokemon-backend-o9nh.onrender.com/game';

// ==========================================
// 2. NETWORK MANAGER CLASS (소켓 통신 전담)
// ==========================================
class NetworkManager {
    constructor(game) {
        this.game = game;
        this.socket = new WebSocket(SERVER_URL);
        this.initEvents();
    }

    initEvents() {
        this.socket.onopen = () => { console.log('📡 WebSocket 도킹 성공:', SERVER_URL); };
        this.socket.onmessage = (event) => this.handleMessage(JSON.parse(event.data));
    }

    handleMessage(data) {
        console.log("📥 수신 패킷:", data);

        if (data.type === 'SIGNUP_RESULT') {
            alert(data.message);
            if (data.success) document.getElementById('password').value = '';
        }
        else if (data.type === 'LOGIN_RESULT') {
            if (!data.success) alert(data.message);
        }
        else if (data.type === 'INIT') {
            this.game.myId = data.myId;
            this.game.players = data.players;
            
            if (this.game.players[data.myId] && this.game.players[data.myId].map) {
                this.game.currentMapName = this.game.players[data.myId].map.toLowerCase();
            }
            this.game.start();
        }
        else if (data.type === 'UPDATE') {
            const safeMapName = data.map ? data.map.toLowerCase() : 'town';
            
            if (this.game.players[data.id]) {
                this.game.players[data.id].x = data.x;
                this.game.players[data.id].y = data.y;
                this.game.players[data.id].map = safeMapName;
            } else {
                this.game.players[data.id] = { x: data.x, y: data.y, map: safeMapName };
            }

            if (data.id === this.game.myId) {
                this.game.currentMapName = safeMapName;
            }
        }
        else if (data.type === 'REMOVE') {
            if (this.game.players[data.id] && this.game.players[data.id].chatTimeout) {
                clearTimeout(this.game.players[data.id].chatTimeout);
            }
            delete this.game.players[data.id];
        }
        else if (data.type === 'CHAT_MESSAGE') {
            this.game.appendChatMessage(data.id, data.msg);
        }
        // 🌾 [추가] 백엔드가 주사위 던져서 포켓몬 조우 신호 보냈을 때 경보 시스템 가동
        else if (data.type === 'WILD_ENCOUNTER') {
            this.game.enterBattle(data);
        }
        else if (data.type === 'BAG_RESULT') {
            this.game.updateBagUI(data.list);
        }
    }

    send(data) {
        if (this.socket.readyState === WebSocket.OPEN) {
            this.socket.send(JSON.stringify(data));
        }
    }
}

// ==========================================
// 3. GAME RENDERER CLASS (캔버스 그래픽 드로잉 전담)
// ==========================================
class GameRenderer {
    constructor(game) {
        this.game = game;
        this.canvas = document.getElementById('myCanvas');
        this.ctx = this.canvas.getContext('2d');
        
        this.charImage = new Image();
        this.charImage.src = 'https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/25.png';
    }

    render() {
        const currentMap = MAPS[this.game.currentMapName] || MAPS['town'];
        
        this.ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);

        // 1. 지형 배경 그리기
        this.ctx.fillStyle = this.game.currentMapName === 'town' ? '#7cfc00' : '#f4a460';
        this.ctx.fillRect(0, 0, this.canvas.width, this.canvas.height);

        // 2. 타일 렌더링 루프
        for (let r = 0; r < 5; r++) {
            for (let c = 0; c < 5; c++) {
                // 🧱 갈색 벽 타일
                if (currentMap[r][c] === 1) {
                    this.ctx.fillStyle = '#8b4513';
                    this.ctx.fillRect(c * TILE_SIZE, r * TILE_SIZE, TILE_SIZE, TILE_SIZE);
                }
                // 🌾 [추가] 진초록색 풀숲 타일 (포켓몬 출몰 구역 비주얼 가이드)
                else if (currentMap[r][c] === 2) {
                    this.ctx.fillStyle = '#228b22'; // Forest Green 색상
                    this.ctx.fillRect(c * TILE_SIZE, r * TILE_SIZE, TILE_SIZE, TILE_SIZE);
                    
                    // 수풀 무늬 디테일 실선 주기 (옵션)
                    this.ctx.strokeStyle = '#006400';
                    this.ctx.lineWidth = 2;
                    this.ctx.strokeRect(c * TILE_SIZE + 10, r * TILE_SIZE + 10, TILE_SIZE - 20, TILE_SIZE - 20);
                }
            }
        }

        // 3. 플레이어 캐릭터 및 이름표, 말풍선 그리기
        for (let id in this.game.players) {
            const p = this.game.players[id];
            if (p.map && p.map !== this.game.currentMapName) continue;

            this.ctx.drawImage(this.charImage, p.x, p.y, CHAR_SIZE, CHAR_SIZE);
            
            this.ctx.font = 'bold 12px Arial';
            if (id === this.game.myId) {
                this.ctx.fillStyle = '#0000ff';
                this.ctx.fillText(id + " (Me)", p.x + 10, p.y - 5);
            } else {
                this.ctx.fillStyle = '#000000';
                this.ctx.fillText(id, p.x + 15, p.y - 5);
            }

            if (p.chatMessage) {
                this.drawBubble(p.chatMessage, p.x, p.y);
            }
        }
    }

    drawBubble(text, x, y) {
        this.ctx.font = '12px sans-serif';
        const textWidth = this.ctx.measureText(text).width;
        const bubbleWidth = textWidth + 16;
        const bubbleHeight = 24;
        
        const bx = x + 32 - (bubbleWidth / 2);
        const by = y - 35;

        this.ctx.fillStyle = 'rgba(255, 255, 255, 0.9)';
        this.ctx.beginPath();
        this.ctx.roundRect(bx, by, bubbleWidth, bubbleHeight, 6);
        this.ctx.fill();
        this.ctx.strokeStyle = '#000000';
        this.ctx.lineWidth = 1;
        this.ctx.stroke();

        this.ctx.beginPath();
        this.ctx.moveTo(x + 32 - 5, by + bubbleHeight);
        this.ctx.lineTo(x + 32 + 5, by + bubbleHeight);
        this.ctx.lineTo(x + 32, by + bubbleHeight + 5);
        this.ctx.closePath();
        this.ctx.fill();
        this.ctx.stroke();

        this.ctx.fillStyle = '#000000';
        this.ctx.textAlign = 'center';
        this.ctx.fillText(text, bx + (bubbleWidth / 2), by + 16);
        this.ctx.textAlign = 'left';
    }
}

// ==========================================
// 4. INPUT HANDLER CLASS (키보드 및 이벤트 통제 전담)
// ==========================================
class InputHandler {
    constructor(game) {
        this.game = game;
        this.chatInput = document.getElementById('chat-input');
        this.initEvents();
    }

    initEvents() {
        window.addEventListener('keydown', (e) => this.handleKeyDown(e));
        this.chatInput.addEventListener('keydown', (e) => this.handleChatKeyDown(e));
    }

    handleKeyDown(e) {
        if (this.game.gameState === 'BATTLE') return;
        if (!this.game.isLoaded || !this.game.myId || !this.game.players[this.game.myId]) return;

        let targetX = this.game.players[this.game.myId].x;
        let targetY = this.game.players[this.game.myId].y;
        let sendMapName = this.game.currentMapName;
        let moved = false;

        if (e.key === 'ArrowUp')    { targetY -= SPEED; moved = true; }
        if (e.key === 'ArrowDown')  { targetY += SPEED; moved = true; }
        if (e.key === 'ArrowLeft')  { targetX -= SPEED; moved = true; }
        if (e.key === 'ArrowRight') { targetX += SPEED; moved = true; }

        if (moved) {
            const canvas = this.game.renderer.canvas;
            
            if (this.game.currentMapName === 'town' && targetX > canvas.width - CHAR_SIZE) {
                sendMapName = 'field';
                targetX = 40;
            }
            else if (this.game.currentMapName === 'field' && targetX < 0) {
                sendMapName = 'town';
                targetX = canvas.width - CHAR_SIZE - 40;
            }
            else {
                if (targetX < 0) targetX = 0;
                if (targetX > canvas.width - CHAR_SIZE) targetX = canvas.width - CHAR_SIZE;
                if (targetY < 0) targetY = 0;
                if (targetY > canvas.height - CHAR_SIZE) targetY = canvas.height - CHAR_SIZE;
            }

            this.game.network.send({
                type: "MOVE",
                x: targetX,
                y: targetY,
                map: sendMapName
            });
        }
    }

    handleChatKeyDown(e) {
        if (e.key === 'Enter') {
            const msg = this.chatInput.value.trim();
            if (msg !== '') {
                this.game.network.send({ type: "CHAT", msg: msg });
                this.chatInput.value = '';
            }
        }
        e.stopPropagation(); 
    }
}

// ==========================================
// 5. MAIN GAME ENGINE SYSTEM (총괄 관리 클래스)
// ==========================================
class PokemonGame {
    constructor() {
        this.myId = null;
        this.players = {};
        this.isLoaded = false;
        this.currentMapName = 'town';
        this.gameState = 'FIELD';
        this.network = new NetworkManager(this);
        this.renderer = new GameRenderer(this);
        this.input = new InputHandler(this);
    }

    start() {
        document.getElementById('auth-container').style.display = 'none';
        document.getElementById('myCanvas').style.display = 'block';
        document.getElementById('game-desc').style.display = 'block';
        document.getElementById('chat-container').style.display = 'block';
        document.getElementById('auth-container').style.display = 'none';
        document.getElementById('myCanvas').style.display = 'block';
        document.getElementById('game-desc').style.display = 'block';
        document.getElementById('chat-container').style.display = 'block';
        document.getElementById('bag-container').style.display = 'block';
        this.isLoaded = true;
        this.loop();
    }

    loop() {
        this.renderer.render();
        requestAnimationFrame(() => this.loop());
    }

    appendChatMessage(id, msg) {
        const chatBox = document.getElementById('chat-box');
        const newMessage = document.createElement('div');
        newMessage.style.marginBottom = '5px';

        newMessage.innerHTML = (id === this.myId)
            ? `<span style="color: #ffcb05; font-weight: bold;">[${id}]:</span> ${msg}`
            : `<span style="color: #5ce6e6; font-weight: bold;">[${id}]:</span> ${msg}`;

        chatBox.appendChild(newMessage);
        chatBox.scrollTop = chatBox.scrollHeight;

        const targetPlayer = this.players[id];
        if (targetPlayer) {
            if (targetPlayer.chatTimeout) clearTimeout(targetPlayer.chatTimeout);
            
            targetPlayer.chatMessage = msg;
            targetPlayer.chatTimeout = setTimeout(() => {
                if (this.players[id]) this.players[id].chatMessage = null;
            }, 4000);
        }
    }
    enterBattle(data) {
        this.gameState = 'BATTLE'; // 락 걸기

        // 1. 적 정보 세팅
        document.getElementById('enemy-name').innerText = `야생의 ${data.pokemonName}`;
        document.getElementById('enemy-level').innerText = `Lv.${data.level}`;
        document.getElementById('enemy-hp-text').innerText = `HP: ${data.hp}/${data.maxHp}`;
        document.getElementById('enemy-hp-bar').style.width = "100%";

        // 2. 내 정보 세팅 (가방 데이터 기반 연동, 일단 껍데기 세팅)
        document.getElementById('battle-log').innerText = `앗! 풀숲에서 야생의 [${data.pokemonName}](이)가 튀어나왔다! \n무엇을 할까?`;

        // 3. UI 스위칭 (필드 숨기고 배틀 레이어 켜기)
        document.getElementById('battle-container').style.display = 'flex';
    }

    // 🏃‍♂️ 배틀 종료 후 필드로 다시 평화롭게 돌려보내는 함수
    exitBattle() {
        this.gameState = 'FIELD'; // 락 해제
        document.getElementById('battle-container').style.display = 'none';
    }
    updateBagUI(pokemonList) {
        const bagListDiv = document.getElementById('bag-list');
        if (pokemonList.length === 0) {
            bagListDiv.innerHTML = "<span style='color:#aaa;'>소지한 포켓몬이 없습니다.</span>";
            return;
        }

        let htmlContent = "";
        pokemonList.forEach((poke, index) => {
            htmlContent += `<div style="margin-bottom:4px; padding-bottom:4px; border-bottom:1px solid #555;">
                <strong>[${index + 1}] ${poke.pokemonName}</strong> (Lv.${poke.level}) 
                <span style="color:#ff4500;"> HP: ${poke.currentHp} / ${poke.maxHp}</span>
            </div>`;
        });
        bagListDiv.innerHTML = htmlContent;
    }
    
}

// ==========================================
// 6. GLOBAL ACTION ROUTERS (HTML 연결 인터페이스)
// ==========================================
const gameInstance = new PokemonGame();

function requestSignup() {
    const u = document.getElementById('username').value.trim();
    const p = document.getElementById('password').value.trim();
    if (!u || !p) return alert('아이디와 비밀번호를 모두 입력해주세요!');
    gameInstance.network.send({ type: "SIGNUP", username: u, password: p });
}

function requestLogin() {
    const u = document.getElementById('username').value.trim();
    const p = document.getElementById('password').value.trim();
    if (!u || !p) return alert('아이디와 비밀번호를 모두 입력해주세요!');
    gameInstance.network.send({ type: "LOGIN", username: u, password: p });
}
function requestMyBag() {
    gameInstance.network.send({ type: "GET_BAG" });
}
function clickAttack() {
    document.getElementById('battle-log').innerText = "💥 피카츄의 백만볼트 공격! (아직 계산 로직 개발 중)";
}
function clickCatch() {
    document.getElementById('battle-log').innerText = "🔴 몬스터볼을 던졌다! (아직 계산 로직 개발 중)";
}
function clickRunaway() {
    // 4단계를 만들기 전 가동 테스트를 위해 도망치기를 누르면 필드로 나가게 임시 연동합니다.
    gameInstance.exitBattle();
}