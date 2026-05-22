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
                if (currentMap[r][c] === 1) {
                    this.ctx.fillStyle = '#8b4513';
                    this.ctx.fillRect(c * TILE_SIZE, r * TILE_SIZE, TILE_SIZE, TILE_SIZE);
                }
                else if (currentMap[r][c] === 2) {
                    this.ctx.fillStyle = '#228b22'; 
                    this.ctx.fillRect(c * TILE_SIZE, r * TILE_SIZE, TILE_SIZE, TILE_SIZE);
                    
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
// 4. INPUT HANDLER CLASS (버그 완벽 수정본)
// ==========================================
class InputHandler {
    constructor(game) {
        this.game = game;
        this.chatInput = document.getElementById('chat-input');
        this.initEvents();
    }

    initEvents() {
        // ⌨️ [PC] 키보드 화살표 감지 리스너
        window.addEventListener('keydown', (e) => this.handleKeyDown(e));
        
        // 💬 채팅창 내부 키보드 감지 리스너
        this.chatInput.addEventListener('keydown', (e) => this.handleChatKeyDown(e));

        // 📱 [모바일 꾹 누르기 연속 이동 엔진 시스템]
        // 특정 방향 버튼을 꾹 누르고 있으면 0.12초마다 자동으로 handleKeyDown을 연사합니다.
        const bindContinuousTouch = (btnId, keyName) => {
            const btn = document.getElementById(btnId);
            if (!btn) return;

            let moveTimer = null; // 연속 이동을 제어할 개별이동이 타이머 주머니

            // 1. 손가락으로 버튼을 대는 순간 (꾹 누르기 시작)
            btn.addEventListener('touchstart', (e) => {
                e.preventDefault(); // 롱터치 시 모바일 브라우저 돋보기/메뉴 팝업 차단
                
                // 터치하자마자 일단 즉시 한 칸 움직이고
                this.handleKeyDown({ key: keyName });

                // 그 뒤로 손을 떼기 전까지 120밀리초(0.12초)마다 무한 연사 루프 기동
                if (moveTimer === null) {
                    moveTimer = setInterval(() => {
                        this.handleKeyDown({ key: keyName });
                    }, 120); 
                }
            });

            // 2. 손가락을 버튼에서 떼는 순간 (이동 정지)
            btn.addEventListener('touchend', (e) => {
                if (moveTimer !== null) {
                    clearInterval(moveTimer); // 연사 주사기 타이머 폭파
                    moveTimer = null;
                }
            });

            // 3. PC 브라우저 마우스 클릭으로 테스트할 때도 똑같이 꾹 누르기가 작동하도록 가드 이식
            btn.addEventListener('mousedown', (e) => {
                e.preventDefault();
                this.handleKeyDown({ key: keyName });

                if (moveTimer === null) {
                    moveTimer = setInterval(() => {
                        this.handleKeyDown({ key: keyName });
                    }, 120);
                }
                window.focus();
            });

            // 마우스를 떼거나, 버튼 바깥으로 마우스가 탈출했을 때 타이머 청소
            const stopMouse = () => {
                if (moveTimer !== null) {
                    clearInterval(moveTimer);
                    moveTimer = null;
                }
            };
            btn.addEventListener('mouseup', stopMouse);
            btn.addEventListener('mouseleave', stopMouse);
        };

        // 새로운 연속 터치 엔진에 스마트폰 D-Pad 버튼들 도킹 완료
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

        // 🛠️ [버그 교정 3]: 대소문자 브라우저 연산 오차 방지를 위해 엄격 모드로 완전 통일 처리
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
        this.gameState = 'BATTLE'; 

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
            htmlContent += `<div style="margin-bottom:4px; padding-bottom:4px; border-bottom:1px solid #555;">
                <strong>[${index + 1}] ${poke.pokemonName}</strong> (Lv.${poke.level}) 
                <span style="color:#ff4500;"> HP: ${poke.currentHp} / ${poke.maxHp}</span>
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
    gameInstance.network.send({ type: "BATTLE_ATTACK" });
}

// 🛠️ [버그 교정 4]: 기존 오타가 있던 clickCatch() 함수 명칭 싱크 보정
function clickCatch() {
    gameInstance.network.send({ type: "BATTLE_CATCH" });
}

function clickRunaway() {
    gameInstance.network.send({ type: "BATTLE_RUN" });
}

function clickSendChat() {
    gameInstance.input.sendChatMessage();
}