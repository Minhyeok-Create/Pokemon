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
        [2, 2, 0, 0, 0], 
        [2, 1, 1, 1, 0], 
        [0, 0, 0, 1, 0]
    ],
    field: [
        [0, 0, 0, 0, 0],
        [0, 1, 1, 1, 2], 
        [0, 0, 0, 2, 2], 
        [0, 1, 0, 1, 0],
        [1, 1, 0, 1, 1]
    ]
};

const IS_LOCALHOST = window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1';
const SERVER_URL = IS_LOCALHOST
    ? 'ws://localhost:8080/game'
    : 'wss://pokemon-backend-o9nh.onrender.com/game';

// ==========================================
// 2. NETWORK MANAGER & POKEMON POOL
// ==========================================
const POKEMON_IMAGE_POOL = {
    "피카츄": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/25.png",
    "피츄": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/172.png",
    "구구": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/16.png",
    "꼬렛": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/19.png",
    "캐터피": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/10.png",
    "아보": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/23.png",
    "파이리": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/4.png",
    "꼬부기": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/7.png",
    "이상해씨": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/1.png"
};

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
        else if (data.type === 'WILD_ENCOUNTER') {
            this.game.enterBattle(data);
        }
        else if (data.type === 'BAG_RESULT') {
            this.game.updateBagUI(data.list);
        }
        else if (data.type === 'BATTLE_ROUND_RESULT') {
            this.game.handleBattleRound(data);
        }
    }

    send(data) {
        if (this.socket.readyState === WebSocket.OPEN) {
            this.socket.send(JSON.stringify(data));
        }
    }
}

// ==========================================
// 3. GAME RENDERER CLASS (골드 버전 도트 패턴 복원 엔진)
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
        const isTown = this.game.currentMapName === 'town';
        
        this.ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);

        // 🎨 닌텐도 클래식 특유의 칼도트 연출을 위해 계단현상 방지 강제 OFF
        this.ctx.imageSmoothingEnabled = false;

        // 🧱 타일 렌더링 루프 (기존 충돌 및 이동 판정 로직 100% 보존)
        for (let r = 0; r < 5; r++) {
            for (let c = 0; c < 5; c++) {
                let tx = c * TILE_SIZE;
                let ty = r * TILE_SIZE;

                // 🌿 [0번 타일]: 포켓몬 골드 연두색 격자 잔디 길 복원
                if (currentMap[r][c] === 0) {
                    this.ctx.fillStyle = isTown ? '#7bc673' : '#ebd28f'; // 기본 바닥
                    this.ctx.fillRect(tx, ty, TILE_SIZE, TILE_SIZE);
                    
                    // 골드버전 고유의 바닥 미세 가로 도트 무늬 디테일 주입
                    this.ctx.fillStyle = isTown ? '#63b55a' : '#dbae5c';
                    for (let dy = 10; dy < TILE_SIZE; dy += 20) {
                        this.ctx.fillRect(tx + 15, ty + dy, 12, 3);
                        this.ctx.fillRect(tx + 50, ty + dy + 8, 12, 3);
                    }
                    // 겉 테두리 은은한 경계선
                    this.ctx.strokeStyle = isTown ? '#6bb762' : '#dfc67c';
                    this.ctx.strokeRect(tx, ty, TILE_SIZE, TILE_SIZE);
                }
                
                // 🧱 [1번 타일]: 골드버전 특유의 '각진 계단형 바위 격벽' 픽셀화
                else if (currentMap[r][c] === 1) {
                    // 바위 베이스 어두운 음영색
                    this.ctx.fillStyle = isTown ? '#316331' : '#8c763e';
                    this.ctx.fillRect(tx, ty, TILE_SIZE, TILE_SIZE);

                    // 계단식 돌출부 묘사 (골드버전 바위산 무늬 복사)
                    this.ctx.fillStyle = isTown ? '#5a9c5a' : '#cca752';
                    this.ctx.fillRect(tx + 4, ty + 4, TILE_SIZE - 8, TILE_SIZE - 8);

                    this.ctx.fillStyle = isTown ? '#8cd68c' : '#fae19c'; // 최고 하이라이트층
                    this.ctx.fillRect(tx + 4, ty + 4, TILE_SIZE - 8, 16);
                    this.ctx.fillRect(tx + 4, ty + 4, 16, TILE_SIZE - 8);

                    // 그림자 골짜기 픽셀 선 장식
                    this.ctx.fillStyle = isTown ? '#183918' : '#524321';
                    this.ctx.fillRect(tx + 30, ty + 35, 20, 6);
                    this.ctx.fillRect(tx + 45, ty + 50, 20, 6);
                    this.ctx.fillRect(tx, ty + TILE_SIZE - 6, TILE_SIZE, 6);
                    this.ctx.fillRect(tx + TILE_SIZE - 6, ty, 6, TILE_SIZE);
                }
                
                // 🌾 [2번 타일]: 골드버전의 상징인 자글자글한 '격자형 촘촘 수풀 무늬' 완벽 구현
                else if (currentMap[r][c] === 2) {
                    // 수풀 베이스 진녹색 배경
                    this.ctx.fillStyle = isTown ? '#104210' : '#655321'; 
                    this.ctx.fillRect(tx, ty, TILE_SIZE, TILE_SIZE);

                    // 4등분 그리드 개별 도트 풀잎 무늬 구현
                    this.ctx.fillStyle = isTown ? '#429c42' : '#cca752';
                    
                    const subSize = TILE_SIZE / 2; // 40px 단위 스플릿
                    for (let si = 0; si < 2; si++) {
                        for (let sj = 0; sj < 2; sj++) {
                            let stx = tx + (si * subSize);
                            let sty = ty + (sj * subSize);
                            
                            // 골드버전 고유의 ∩ 자형 무늬 풀숲 픽셀 드로우
                            this.ctx.fillRect(stx + 10, sty + 12, 20, 6);
                            this.ctx.fillRect(stx + 6, sty + 18, 6, 16);
                            this.ctx.fillRect(stx + 28, sty + 18, 6, 16);
                            this.ctx.fillRect(stx + 14, sty + 22, 12, 6);
                            
                            // 풀숲 내부 명암 포인트 점박이 추가
                            this.ctx.fillStyle = isTown ? '#a5e7a5' : '#fae19c';
                            this.ctx.fillRect(stx + 16, sty + 14, 8, 3);
                            this.ctx.fillStyle = isTown ? '#429c42' : '#cca752'; // 색상 복구
                        }
                    }
                    // 수풀 경계 마감 검은 실선 테두리
                    this.ctx.strokeStyle = isTown ? '#082108' : '#423210';
                    this.ctx.lineWidth = 2;
                    this.ctx.strokeRect(tx + 2, ty + 2, TILE_SIZE - 4, TILE_SIZE - 4);
                }
            }
        }

        // 4. 플레이어 캐릭터 및 이름표, 말풍선 그리기 (기존 멀티 동기화 철저 보존)
        for (let id in this.game.players) {
            const p = this.game.players[id];
            if (p.map && p.map !== this.game.currentMapName) continue;

            this.ctx.drawImage(this.charImage, p.x, p.y, CHAR_SIZE, CHAR_SIZE);
            
            this.ctx.font = 'bold 12px Arial';
            if (id === this.game.myId) {
                this.ctx.fillStyle = 'rgba(0, 0, 0, 0.6)';
                this.ctx.fillRect(p.x - 4, p.y - 18, this.ctx.measureText(id + " (Me)").width + 12, 16);
                this.ctx.fillStyle = '#ffcb05';
                this.ctx.fillText(id + " (Me)", p.x + 2, p.y - 6);
            } else {
                this.ctx.fillStyle = 'rgba(0, 0, 0, 0.4)';
                this.ctx.fillRect(p.x + 4, p.y - 18, this.ctx.measureText(id).width + 10, 16);
                this.ctx.fillStyle = '#ffffff';
                this.ctx.fillText(id, p.x + 9, p.y - 6);
            }

            if (p.chatMessage) {
                this.drawBubble(p.chatMessage, p.x, p.y);
            }
        }
    }

    drawBubble(text, x, y) {
        this.ctx.font = '12px sans-serif';
        const textWidth = this.ctx.measureText(text).width;
        const bubbleWidth = Math.max(40, textWidth + 16);
        const bubbleHeight = 24;
        
        const bx = x + 32 - (bubbleWidth / 2);
        const by = y - 48;

        this.ctx.fillStyle = 'rgba(255, 255, 255, 0.95)';
        this.ctx.beginPath();
        this.ctx.roundRect(bx, by, bubbleWidth, bubbleHeight, 8);
        this.ctx.fill();
        this.ctx.strokeStyle = '#ffcb05';
        this.ctx.lineWidth = 2;
        this.ctx.stroke();

        this.ctx.fillStyle = 'rgba(255, 255, 255, 0.95)';
        this.ctx.beginPath();
        this.ctx.moveTo(x + 32 - 6, by + bubbleHeight);
        this.ctx.lineTo(x + 32 + 6, by + bubbleHeight);
        this.ctx.lineTo(x + 32, by + bubbleHeight + 6);
        this.ctx.closePath();
        this.ctx.fill();
        this.ctx.stroke();

        this.ctx.fillStyle = '#111111';
        this.ctx.textAlign = 'center';
        this.ctx.font = 'bold 12px sans-serif';
        this.ctx.fillText(text, bx + (bubbleWidth / 2), by + 16);
        this.ctx.textAlign = 'left';
    }
}

// ==========================================
// 4. INPUT HANDLER CLASS (조이스틱 패드 꾹 누르기 기믹 완벽 작동)
// ==========================================
class InputHandler {
    constructor(game) {
        this.chatInput = document.getElementById('chat-input');
        this.game = game;
        this.initEvents();
    }

    initEvents() {
        window.addEventListener('keydown', (e) => this.handleKeyDown(e));
        this.chatInput.addEventListener('keydown', (e) => this.handleChatKeyDown(e));

        const bindContinuousTouch = (btnId, keyName) => {
            const btn = document.getElementById(btnId);
            if (!btn) return;

            let moveTimer = null; 

            const startMoving = (e) => {
                e.preventDefault(); 
                this.handleKeyDown({ key: keyName });

                if (moveTimer === null) {
                    moveTimer = setInterval(() => {
                        this.handleKeyDown({ key: keyName });
                    }, 120); 
                }
            };

            const stopMoving = () => {
                if (moveTimer !== null) {
                    clearInterval(moveTimer); 
                    moveTimer = null;
                }
            };

            btn.addEventListener('touchstart', startMoving);
            btn.addEventListener('touchend', stopMoving);

            btn.addEventListener('mousedown', (e) => {
                startMoving(e);
                window.focus(); 
            });
            btn.addEventListener('mouseup', stopMoving);
            btn.addEventListener('mouseleave', stopMoving);
        };

        bindContinuousTouch('btn-up', 'ArrowUp');
        bindContinuousTouch('btn-down', 'ArrowDown');
        bindContinuousTouch('btn-left', 'ArrowLeft');
        bindContinuousTouch('btn-right', 'ArrowRight');
    }

    sendChatMessage() {
        const msg = this.chatInput.value.trim();
        if (msg === '') return;

        this.game.network.send({
            type: "CHAT",
            msg: msg
        });
        
        this.chatInput.value = ''; 
    }

    handleKeyDown(e) {
        if (this.game.gameState === 'BATTLE') return;
        if (!this.game.isLoaded || !this.game.myId || !this.game.players[this.game.myId]) return;

        let targetX = this.game.players[this.game.myId].x;
        let targetY = this.game.players[this.game.myId].y;
        let sendMapName = this.game.currentMapName;
        let moved = false;

        const key = e.key;
        if (key === 'ArrowUp' || key === 'up')       { targetY -= SPEED; moved = true; }
        if (key === 'ArrowDown' || key === 'down')   { targetY += SPEED; moved = true; }
        if (key === 'ArrowLeft' || key === 'left')   { targetX -= SPEED; moved = true; }
        if (key === 'ArrowRight' || key === 'right') { targetX += SPEED; moved = true; }

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
            this.sendChatMessage(); 
        }
        e.stopPropagation(); 
    }
}

// ==========================================
// 5. MAIN GAME ENGINE SYSTEM
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
        newMessage.style.marginBottom = '6px';
        newMessage.style.padding = '3px 6px';
        newMessage.style.borderRadius = '4px';
        newMessage.style.background = 'rgba(0,0,0,0.2)';

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
        this.gameState = 'BATTLE'; 
        const myImgEl = document.getElementById('battle-my-img');
        const enemyImgEl = document.getElementById('battle-enemy-img');
        
        if (myImgEl) {
            myImgEl.src = "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/25.png"; 
        }
        
        if (enemyImgEl) {
            const cleanedName = data.pokemonName ? data.pokemonName.trim() : "";
            const matchedImg = POKEMON_IMAGE_POOL[cleanedName] || POKEMON_IMAGE_POOL["구구"];
            enemyImgEl.src = matchedImg;
        }
        document.getElementById('enemy-name').innerText = `야생의 ${data.pokemonName}`;
        document.getElementById('enemy-level').innerText = `Lv.${data.level}`;
        document.getElementById('enemy-hp-text').innerText = `HP: ${data.hp}/${data.maxHp}`;
        document.getElementById('enemy-hp-bar').style.width = "100%";
        document.getElementById('enemy-hp-bar').style.backgroundColor = "#00ff00";

        document.getElementById('battle-log').innerText = `앗! 풀숲에서 야생의 [${data.pokemonName}](이)가 튀어나왔다! \n무엇을 할까?`;
        document.getElementById('battle-container').style.display = 'flex';
    }

    exitBattle() {
        this.gameState = 'FIELD'; 
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
            htmlContent += `<div style="margin-bottom:6px; padding:6px; border-bottom:1px solid #333; background: rgba(0,0,0,0.15); border-radius: 4px; display: flex; justify-content: space-between;">
                <span><strong>[${index + 1}] ${poke.pokemonName}</strong> <span style="color:#aaa; font-size:11px;">Lv.${poke.level}</span></span>
                <span style="color:#ff5353; font-weight:bold;">❤️ HP ${poke.currentHp}/${poke.maxHp}</span>
            </div>`;
        });
        bagListDiv.innerHTML = htmlContent;
    }

    handleBattleRound(data) {
        document.getElementById('battle-log').innerText = data.log;

        if (data.enemyHp !== undefined) {
            const hpBar = document.getElementById('enemy-hp-bar');
            const hpText = document.getElementById('enemy-hp-text');
            const currentMax = parseInt(hpText.innerText.split('/')[1]);
            const ratio = (data.enemyHp / currentMax) * 100;
            
            hpBar.style.width = `${ratio}%`;
            hpText.innerText = `HP: ${data.enemyHp}/${currentMax}`;
            hpBar.style.backgroundColor = ratio < 30 ? "#ff5353" : "#00ff00"; 
        }

        if (data.myPokemonHp !== undefined && data.myPokemonMaxHp !== undefined) {
            const myHpBar = document.getElementById('my-pokemon-hp-bar');
            const myHpText = document.getElementById('my-pokemon-hp-text');
            const myRatio = (data.myPokemonHp / data.myPokemonMaxHp) * 100;

            myHpBar.style.width = `${myRatio}%`;
            myHpText.innerText = `HP: ${data.myPokemonHp}/${data.myPokemonMaxHp}`;
            
            if (myRatio < 30) {
                myHpBar.style.backgroundColor = "#ff5353"; 
            } else if (myRatio < 60) {
                myHpBar.style.backgroundColor = "#ffcb05"; 
            } else {
                myHpBar.style.backgroundColor = "#00ff00"; 
            }
        }

        if (data.battleEnded) {
            setTimeout(() => {
                this.exitBattle();
                if (typeof requestMyBag === 'function') requestMyBag();
            }, 2500);
        }
    }
}

// ==========================================
// 6. GLOBAL ACTION ROUTERS
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
    gameInstance.network.send({ type: "BATTLE_ATTACK" });
}

function clickCatch() {
    gameInstance.network.send({ type: "BATTLE_CATCH" });
}

function clickRunaway() {
    gameInstance.network.send({ type: "BATTLE_RUN" });
}

function clickSendChat() {
    gameInstance.input.sendChatMessage();
}