// ==========================================
// 1. CONSTANTS & CONFIGURATIONS
// ==========================================
const TILE_SIZE = 80;
const SPEED = 8;
const CHAR_SIZE = 48;

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
    // 🍃 일반 등급 (Common)
    "구구": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/16.png",
    "꼬렛": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/19.png",
    "캐터피": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/10.png",
    "아보": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/23.png",
    "피츄": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/172.png",
    "모래두지": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/27.png",
    "뚜벅초": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/43.png",
    "발챙이": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/60.png",
    "디그다": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/50.png",
    "주뱃": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/41.png",

    // ✨ 레어 등급 (Rare)
    "피카츄": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/25.png",
    "파이리": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/4.png",
    "꼬부기": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/7.png",
    "이상해씨": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/1.png",
    "이브이": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/133.png",
    "가디": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/58.png",
    "고스트": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/93.png",
    "롱스톤": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/95.png",

    // 🌌 환상 등급 (Mystic)
    "미뇽": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/147.png",
    "잠만보": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/143.png",
    "켄타로스": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/128.png",
    "라프라스": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/131.png",

    // 👑 초전설 등급 (Legendary)
    "뮤츠": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/150.png",
    "루기아": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/249.png",
    "칠색조": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/250.png"
};

const POKEMON_BACK_IMAGE_POOL = {
    // 🍃 일반 등급 뒷모습
    "구구": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/16.png",
    "꼬렛": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/19.png",
    "캐터피": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/10.png",
    "아보": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/23.png",
    "피츄": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/172.png",
    "모래두지": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/27.png",
    "뚜벅초": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/43.png",
    "발챙이": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/60.png",
    "디그다": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/50.png",
    "주뱃": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/41.png",

    // ✨ 레어 등급 뒷모습
    "피카츄": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/25.png",
    "파이리": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/4.png",
    "꼬부기": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/7.png",
    "이상해씨": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/1.png",
    "이브이": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/133.png",
    "가디": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/58.png",
    "고스트": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/93.png",
    "롱스톤": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/95.png",

    // 🌌 환상 등급 뒷모습
    "미뇽": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/147.png",
    "잠만보": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/143.png",
    "켄타로스": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/128.png",
    "라프라스": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/131.png",

    // 👑 초전설 등급 뒷모습
    "뮤츠": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/150.png",
    "루기아": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/249.png",
    "칠색조": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/back/250.png"
};

class NetworkManager {
    constructor(game) {
        this.game = game;
        this.socket = new WebSocket(SERVER_URL);
        this.initEvents();
    }

    initEvents() {
        this.socket.onopen = () => { console.log('📡 WebSocket 연결 완료:', SERVER_URL); };
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
            
            if (this.game.players[data.myId]) {
                this.game.players[data.myId].direction = 'down';
                this.game.players[data.myId].frame = 0;
                this.game.players[data.myId].stepCounter = 0;
            }

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
                if (data.direction) this.game.players[data.id].direction = data.direction;
                this.game.players[data.id].frame = data.frame !== undefined ? data.frame : 0;
            } else {
                this.game.players[data.id] = { x: data.x, y: data.y, map: safeMapName, direction: data.direction || 'down', frame: 0 };
            }
            if (data.id === this.game.myId) this.game.currentMapName = safeMapName;
        }
        else if (data.type === 'REMOVE') {
            if (this.game.players[data.id] && this.game.players[data.id].chatTimeout) clearTimeout(this.game.players[data.id].chatTimeout);
            delete this.game.players[data.id];
        }
        else if (data.type === 'CHAT_MESSAGE') {
            this.game.appendChatMessage(data.id, data.msg);
        }
        else if (data.type === 'WILD_ENCOUNTER') {
            if (this.game.input && typeof this.game.input.forceStopMoving === 'function') {
                this.game.input.forceStopMoving();
            }
            this.game.enterBattle(data);
        }
        else if (data.type === 'BAG_RESULT') {
            this.game.updateBagUI(data.list);
            this.game.updateBattleChangeZone(data.list); 
        }
        else if (data.type === 'BATTLE_ROUND_RESULT') {
            this.game.handleBattleRound(data);
        }
        else if (data.type === 'CENTER_HEAL_RESULT') {
            const chatBox = document.getElementById('chat-box');
            const newMessage = document.createElement('div');
            newMessage.style.marginBottom = '6px'; newMessage.style.padding = '3px 6px'; newMessage.style.borderRadius = '4px'; newMessage.style.background = 'rgba(46, 204, 113, 0.2)';
            newMessage.innerHTML = `<span style="color: #2ecc71; font-weight: bold;">[포켓몬센터]:</span> ${data.log}`;
            chatBox.appendChild(newMessage); chatBox.scrollTop = chatBox.scrollHeight;
        }
    }

    send(data) {
        if (this.socket.readyState === WebSocket.OPEN) this.socket.send(JSON.stringify(data));
    }
}

// ==========================================
// 3. GAME RENDERER CLASS
// ==========================================
class GameRenderer {
    constructor(game) {
        this.game = game;
        this.canvas = document.getElementById('myCanvas');
        this.ctx = this.canvas.getContext('2d');
    }

    drawPixelSprite(direction, frame, cx, cy, isMe) {
        this.ctx.save();
        this.ctx.translate(cx - 24, cy - 24); 
        const P = 3; 
        const WHITE = '#ffffff'; const SKIN = '#ffd1a4'; const BLUE_PANTS = '#2c3e50'; const BLACK = '#111111'; const BAG = '#e67e22';
        const THEME_COLOR = isMe ? '#cc2222' : '#3498db'; 

        let bobY = (frame === 1 || frame === 3) ? P : 0;
        let legSwitch = (frame === 1) ? 1 : (frame === 3) ? 2 : 0;

        this.ctx.fillStyle = 'rgba(0,0,0,0.25)'; this.ctx.fillRect(4*P, 14*P, 8*P, 2*P);

        if (direction === 'down') {
            this.ctx.fillStyle = THEME_COLOR; this.ctx.fillRect(4*P, 1*P + bobY, 8*P, 3*P);
            this.ctx.fillStyle = WHITE;       this.ctx.fillRect(4*P, 4*P + bobY, 8*P, 1*P);
            this.ctx.fillStyle = THEME_COLOR; this.ctx.fillRect(3*P, 5*P + bobY, 10*P, 1*P);
            this.ctx.fillStyle = SKIN;        this.ctx.fillRect(4*P, 6*P + bobY, 8*P, 3*P);
            this.ctx.fillStyle = BLACK;       this.ctx.fillRect(5*P, 7*P + bobY, 1*P, 1*P); this.ctx.fillRect(10*P, 7*P + bobY, 1*P, 1*P);
            this.ctx.fillStyle = THEME_COLOR; this.ctx.fillRect(3*P, 9*P + bobY, 10*P, 3*P);
            this.ctx.fillStyle = WHITE;       this.ctx.fillRect(7*P, 9*P + bobY, 2*P, 3*P);
            this.ctx.fillStyle = BLUE_PANTS;
            if (legSwitch === 1) {
                this.ctx.fillRect(4*P, 12*P, 3*P, 2*P); this.ctx.fillStyle = BLACK; this.ctx.fillRect(4*P, 14*P, 3*P, 1*P);
                this.ctx.fillStyle = BLUE_PANTS; this.ctx.fillRect(9*P, 12*P + bobY, 3*P, 2*P); this.ctx.fillStyle = BLACK; this.ctx.fillRect(9*P, 14*P + bobY, 3*P, 1*P);
            } else if (legSwitch === 2) {
                this.ctx.fillRect(4*P, 12*P + bobY, 3*P, 2*P); this.ctx.fillStyle = BLACK; this.ctx.fillRect(4*P, 14*P + bobY, 3*P, 1*P);
                this.ctx.fillStyle = BLUE_PANTS; this.ctx.fillRect(9*P, 12*P, 3*P, 2*P); this.ctx.fillStyle = BLACK; this.ctx.fillRect(9*P, 14*P, 3*P, 1*P);
            } else {
                this.ctx.fillRect(4*P, 12*P, 3*P, 2*P); this.ctx.fillRect(9*P, 12*P, 3*P, 2*P);
                this.ctx.fillStyle = BLACK; this.ctx.fillRect(4*P, 14*P, 3*P, 1*P); this.ctx.fillRect(9*P, 14*P, 3*P, 1*P);
            }
        } 
        else if (direction === 'up') {
            this.ctx.fillStyle = THEME_COLOR; this.ctx.fillRect(4*P, 1*P + bobY, 8*P, 4*P);
            this.ctx.fillStyle = BLACK;       this.ctx.fillRect(4*P, 5*P + bobY, 8*P, 1*P);
            this.ctx.fillStyle = BAG;         this.ctx.fillRect(3*P, 6*P + bobY, 10*P, 6*P);
            this.ctx.fillStyle = BLACK;       this.ctx.fillRect(4*P, 8*P + bobY, 8*P, 1*P);
            this.ctx.fillStyle = BLUE_PANTS;
            if (legSwitch === 1) {
                this.ctx.fillRect(4*P, 12*P, 3*P, 2*P); this.ctx.fillStyle = BLACK; this.ctx.fillRect(4*P, 14*P, 3*P, 1*P);
                this.ctx.fillStyle = BLUE_PANTS; this.ctx.fillRect(9*P, 12*P + bobY, 3*P, 2*P); this.ctx.fillStyle = BLACK; this.ctx.fillRect(9*P, 14*P + bobY, 3*P, 1*P);
            } else if (legSwitch === 2) {
                this.ctx.fillRect(4*P, 12*P + bobY, 3*P, 2*P); this.ctx.fillStyle = BLACK; this.ctx.fillRect(4*P, 14*P + bobY, 3*P, 1*P);
                this.ctx.fillStyle = BLUE_PANTS; this.ctx.fillRect(9*P, 12*P, 3*P, 2*P); this.ctx.fillStyle = BLACK; this.ctx.fillRect(9*P, 14*P, 3*P, 1*P);
            } else {
                this.ctx.fillRect(4*P, 12*P, 3*P, 2*P); this.ctx.fillRect(9*P, 12*P, 3*P, 2*P);
                this.ctx.fillStyle = BLACK; this.ctx.fillRect(4*P, 14*P, 3*P, 1*P); this.ctx.fillRect(9*P, 14*P, 3*P, 1*P);
            }
        } 
        else if (direction === 'left') {
            this.ctx.fillStyle = THEME_COLOR; this.ctx.fillRect(5*P, 1*P + bobY, 7*P, 3*P);
            this.ctx.fillStyle = WHITE;       this.ctx.fillRect(5*P, 4*P + bobY, 7*P, 1*P);
            this.ctx.fillStyle = THEME_COLOR; this.ctx.fillRect(2*P, 5*P + bobY, 10*P, 1*P);
            this.ctx.fillStyle = SKIN;        this.ctx.fillRect(5*P, 6*P + bobY, 7*P, 3*P);
            this.ctx.fillStyle = BLACK;       this.ctx.fillRect(6*P, 7*P + bobY, 1*P, 1*P);
            this.ctx.fillStyle = THEME_COLOR; this.ctx.fillRect(4*P, 9*P + bobY, 8*P, 3*P);
            this.ctx.fillStyle = BAG;         this.ctx.fillRect(10*P, 9*P + bobY, 2*P, 3*P);
            this.ctx.fillStyle = BLUE_PANTS;
            if (legSwitch !== 0) { 
                this.ctx.fillRect(4*P, 12*P, 4*P, 2*P); this.ctx.fillRect(8*P, 12*P + P, 4*P, 1*P);
                this.ctx.fillStyle = BLACK; this.ctx.fillRect(4*P, 14*P, 4*P, 1*P);
            } else { 
                this.ctx.fillRect(4*P, 12*P, 7*P, 2*P);
                this.ctx.fillStyle = BLACK; this.ctx.fillRect(4*P, 14*P, 7*P, 1*P);
            }
        } 
        else if (direction === 'right') {
            this.ctx.fillStyle = THEME_COLOR; this.ctx.fillRect(4*P, 1*P + bobY, 7*P, 3*P);
            this.ctx.fillStyle = WHITE;       this.ctx.fillRect(4*P, 4*P + bobY, 7*P, 1*P);
            this.ctx.fillStyle = THEME_COLOR; this.ctx.fillRect(4*P, 5*P + bobY, 10*P, 1*P);
            this.ctx.fillStyle = SKIN;        this.ctx.fillRect(4*P, 6*P + bobY, 7*P, 3*P);
            this.ctx.fillStyle = BLACK;       this.ctx.fillRect(9*P, 7*P + bobY, 1*P, 1*P);
            this.ctx.fillStyle = THEME_COLOR; this.ctx.fillRect(4*P, 9*P + bobY, 8*P, 3*P);
            this.ctx.fillStyle = BAG;         this.ctx.fillRect(4*P, 9*P + bobY, 2*P, 3*P);
            this.ctx.fillStyle = BLUE_PANTS;
            if (legSwitch !== 0) { 
                this.ctx.fillRect(4*P, 12*P + P, 4*P, 1*P); this.ctx.fillRect(8*P, 12*P, 4*P, 2*P);
                this.ctx.fillStyle = BLACK; this.ctx.fillRect(8*P, 14*P, 4*P, 1*P);
            } else { 
                this.ctx.fillRect(5*P, 12*P, 7*P, 2*P);
                this.ctx.fillStyle = BLACK; this.ctx.fillRect(5*P, 14*P, 7*P, 1*P);
            }
        }
        this.ctx.restore();
    }

    render() {
        const currentMap = MAPS[this.game.currentMapName] || MAPS['town'];
        const isTown = this.game.currentMapName === 'town';
        this.ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);
        this.ctx.imageSmoothingEnabled = false;

        for (let r = 0; r < 5; r++) {
            for (let c = 0; c < 5; c++) {
                let tx = c * TILE_SIZE; let ty = r * TILE_SIZE;
                if (currentMap[r][c] === 0) {
                    this.ctx.fillStyle = isTown ? '#7bc673' : '#dcd095'; this.ctx.fillRect(tx, ty, TILE_SIZE, TILE_SIZE);
                    this.ctx.fillStyle = isTown ? '#63b55a' : '#dbae5c';
                    for (let dy = 10; dy < TILE_SIZE; dy += 20) { this.ctx.fillRect(tx + 15, ty + dy, 12, 3); this.ctx.fillRect(tx + 50, ty + dy + 8, 12, 3); }
                    this.ctx.strokeStyle = isTown ? '#6bb762' : '#dfc67c'; this.ctx.strokeRect(tx, ty, TILE_SIZE, TILE_SIZE);
                }
                else if (currentMap[r][c] === 1) {
                    this.ctx.fillStyle = isTown ? '#316331' : '#8c763e'; this.ctx.fillRect(tx, ty, TILE_SIZE, TILE_SIZE);
                    this.ctx.fillStyle = isTown ? '#5a9c5a' : '#cca752'; this.ctx.fillRect(tx + 4, ty + 4, TILE_SIZE - 8, TILE_SIZE - 8);
                    this.ctx.fillStyle = isTown ? '#8cd68c' : '#fae19c'; this.ctx.fillRect(tx + 4, ty + 4, TILE_SIZE - 8, 16); this.ctx.fillRect(tx + 4, ty + 4, 16, TILE_SIZE - 8);
                    this.ctx.fillStyle = isTown ? '#183918' : '#524321'; this.ctx.fillRect(tx + 30, ty + 35, 20, 6); this.ctx.fillRect(tx + 45, ty + 50, 20, 6);
                }
                else if (currentMap[r][c] === 2) {
                    this.ctx.fillStyle = isTown ? '#104210' : '#655321'; this.ctx.fillRect(tx, ty, TILE_SIZE, TILE_SIZE);
                    this.ctx.fillStyle = isTown ? '#429c42' : '#cca752';
                    const subSize = TILE_SIZE / 2;
                    for (let si = 0; si < 2; si++) {
                        for (let sj = 0; sj < 2; sj++) {
                            let stx = tx + (si * subSize); let sty = ty + (sj * subSize);
                            this.ctx.fillRect(stx + 10, sty + 12, 20, 6); this.ctx.fillRect(stx + 6, sty + 18, 6, 16); this.ctx.fillRect(stx + 28, sty + 18, 6, 16); this.ctx.fillRect(stx + 14, sty + 22, 12, 6);
                            this.ctx.fillStyle = isTown ? '#a5e7a5' : '#fae19c'; this.ctx.fillRect(stx + 16, sty + 14, 8, 3);
                            this.ctx.fillStyle = isTown ? '#429c42' : '#cca752';
                        }
                    }
                    this.ctx.strokeStyle = isTown ? '#082108' : '#423210'; this.ctx.strokeRect(tx + 2, ty + 2, TILE_SIZE - 4, TILE_SIZE - 4);
                }
                else if (currentMap[r][c] === 3) {
                    this.ctx.fillStyle = '#3a5fcd'; this.ctx.fillRect(tx, ty, TILE_SIZE, TILE_SIZE); 
                    this.ctx.fillStyle = '#eeeeee'; this.ctx.fillRect(tx + 10, ty + 25, TILE_SIZE - 20, TILE_SIZE - 25); 
                    this.ctx.fillStyle = '#ff0000'; this.ctx.fillRect(tx + 35, ty + 40, 10, 25); this.ctx.fillRect(tx + 27, ty + 47, 26, 10);
                    this.ctx.strokeStyle = '#222'; this.ctx.lineWidth = 2; this.ctx.strokeRect(tx, ty, TILE_SIZE, TILE_SIZE);
                }
            }
        }

        for (let id in this.game.players) {
            const p = this.game.players[id];
            if (p.map && p.map !== this.game.currentMapName) continue;
            const currentDir = p.direction || 'down'; const currentFrame = p.frame !== undefined ? p.frame : 0;
            const centerX = p.x + (TILE_SIZE / 2); const centerY = p.y + (TILE_SIZE / 2);
            const isMe = (id === this.game.myId);
            this.drawPixelSprite(currentDir, currentFrame, centerX, centerY, isMe);
            
            this.ctx.font = 'bold 12px Arial';
            if (isMe) {
                this.ctx.fillStyle = 'rgba(0, 0, 0, 0.6)'; this.ctx.fillRect(p.x - 4, p.y - 18, this.ctx.measureText(id + " (Me)").width + 12, 16);
                this.ctx.fillStyle = '#ffcb05'; this.ctx.fillText(id + " (Me)", p.x + 2, p.y - 6);
            } else {
                this.ctx.fillStyle = 'rgba(0, 0, 0, 0.4)'; this.ctx.fillRect(p.x + 4, p.y - 18, this.ctx.measureText(id).width + 10, 16);
                this.ctx.fillStyle = '#5ce6e6'; this.ctx.fillText(id, p.x + 9, p.y - 6);
            }
            if (p.chatMessage) this.drawBubble(p.chatMessage, p.x, p.y);
        }
    }

    drawBubble(text, x, y) {
        const textWidth = this.ctx.measureText(text).width; const bubbleWidth = Math.max(40, textWidth + 16); const bubbleHeight = 24;
        const bx = x + 32 - (bubbleWidth / 2); const by = y - 48;
        this.ctx.fillStyle = 'rgba(255, 255, 255, 0.95)'; this.ctx.beginPath(); this.ctx.roundRect(bx, by, bubbleWidth, bubbleHeight, 8); this.ctx.fill();
        this.ctx.strokeStyle = '#ffcb05'; this.ctx.lineWidth = 2; this.ctx.stroke();
        this.ctx.beginPath(); this.ctx.moveTo(x + 32 - 6, by + bubbleHeight); this.ctx.lineTo(x + 32 + 6, by + bubbleHeight); this.ctx.lineTo(x + 32, by + bubbleHeight + 6); this.ctx.closePath(); this.ctx.fill(); this.ctx.stroke();
        this.ctx.fillStyle = '#111111'; this.ctx.textAlign = 'center'; this.ctx.font = 'bold 12px sans-serif'; this.ctx.fillText(text, bx + (bubbleWidth / 2), by + 16); this.ctx.textAlign = 'left';
    }
}

// ==========================================
// 4. INPUT HANDLER CLASS
// ==========================================
class InputHandler {
    constructor(game) {
        this.chatInput = document.getElementById('chat-input');
        this.game = game;
        this.hasHealedOnThisTile = false; 
        this.activeTouchTimers = []; 
        this.initEvents();
    }

    initEvents() {
        window.addEventListener('keydown', (e) => this.handleKeyDown(e));
        window.addEventListener('keyup', (e) => this.handleKeyUp(e)); 
        this.chatInput.addEventListener('keydown', (e) => this.handleChatKeyDown(e));

        const bindContinuousTouch = (btnId, keyName) => {
            const btn = document.getElementById(btnId); if (!btn) return;
            let moveTimer = null; 
            const startMoving = (e) => {
                if (this.game.gameState === 'BATTLE') return;
                e.preventDefault(); this.handleKeyDown({ key: keyName });
                if (moveTimer === null) {
                    moveTimer = setInterval(() => { this.handleKeyDown({ key: keyName }); }, 120); 
                    this.activeTouchTimers.push(moveTimer); 
                }
            };
            const stopMoving = () => {
                if (moveTimer !== null) {
                    clearInterval(moveTimer);
                    this.activeTouchTimers = this.activeTouchTimers.filter(t => t !== moveTimer);
                    moveTimer = null;
                }
                this.handleKeyUp({ key: keyName }); 
            };
            btn.addEventListener('touchstart', startMoving); btn.addEventListener('touchend', stopMoving);
            btn.addEventListener('mousedown', (e) => { startMoving(e); window.focus(); }); btn.addEventListener('mouseup', stopMoving); btn.addEventListener('mouseleave', stopMoving);
        };
        bindContinuousTouch('btn-up', 'ArrowUp'); bindContinuousTouch('btn-down', 'ArrowDown'); bindContinuousTouch('btn-left', 'ArrowLeft'); bindContinuousTouch('btn-right', 'ArrowRight');
    }

    forceStopMoving() {
        console.log("🚨 [하드록 브레이크] 타이머 폭파!");
        this.activeTouchTimers.forEach(t => clearInterval(t));
        this.activeTouchTimers = [];
        if (this.game.myId && this.game.players[this.game.myId]) {
            this.game.players[this.game.myId].frame = 0;
            this.game.players[this.game.myId].stepCounter = 0;
        }
    }

    sendChatMessage() {
        const msg = this.chatInput.value.trim(); if (msg === '') return;
        this.game.network.send({ type: "CHAT", msg: msg }); this.chatInput.value = ''; 
    }

    handleKeyDown(e) {
        if (this.game.gameState === 'BATTLE') return; 
        if (!this.game.isLoaded || !this.game.myId || !this.game.players[this.game.myId]) return;
        let myPlayer = this.game.players[this.game.myId];
        let targetX = myPlayer.x; let targetY = myPlayer.y; let sendMapName = this.game.currentMapName; let moved = false;
        const key = e.key;

        if (key === 'ArrowUp' || key === 'up')       { targetY -= SPEED; moved = true; myPlayer.direction = 'up'; }
        if (key === 'ArrowDown' || key === 'down')   { targetY += SPEED; moved = true; myPlayer.direction = 'down'; }
        if (key === 'ArrowLeft' || key === 'left')   { targetX -= SPEED; moved = true; myPlayer.direction = 'left'; }
        if (key === 'ArrowRight' || key === 'right') { targetX += SPEED; moved = true; myPlayer.direction = 'right'; }

        if (moved) {
            myPlayer.stepCounter++;
            if (myPlayer.stepCounter >= 3) { myPlayer.frame = (myPlayer.frame + 1) % 4; myPlayer.stepCounter = 0; }
            const canvas = this.game.renderer.canvas;
            if (this.game.currentMapName === 'town' && targetX > canvas.width - CHAR_SIZE) { sendMapName = 'field'; targetX = 40; }
            else if (this.game.currentMapName === 'field' && targetX < 0) { sendMapName = 'town'; targetX = canvas.width - CHAR_SIZE - 40; }
            else {
                if (targetX < 0) targetX = 0; if (targetX > canvas.width - CHAR_SIZE) targetX = canvas.width - CHAR_SIZE;
                if (targetY < 0) targetY = 0; if (targetY > canvas.height - CHAR_SIZE) targetY = canvas.height - CHAR_SIZE;
            }

            const currentMap = MAPS[sendMapName] || MAPS['town'];
            const gridC = Math.floor((targetX + CHAR_SIZE/2) / TILE_SIZE); const gridR = Math.floor((targetY + CHAR_SIZE/2) / TILE_SIZE);
            if (gridR >= 0 && gridR < 5 && gridC >= 0 && gridC < 5) {
                if (currentMap[gridR][gridC] === 3) {
                    if (!this.hasHealedOnThisTile) { this.game.network.send({ type: "HEAL_ALL" }); this.hasHealedOnThisTile = true; }
                } else { this.hasHealedOnThisTile = false; }
            }
            this.game.network.send({ type: "MOVE", x: targetX, y: targetY, map: sendMapName, direction: myPlayer.direction, frame: myPlayer.frame });
        }
    }

    handleKeyUp(e) {
        if (this.game.gameState === 'BATTLE') return;
        if (!this.game.isLoaded || !this.game.myId || !this.game.players[this.game.myId]) return;
        let myPlayer = this.game.players[this.game.myId]; const key = e.key;
        if (key === 'ArrowUp' || key === 'up' || key === 'ArrowDown' || key === 'down' || key === 'ArrowLeft' || key === 'left' || key === 'ArrowRight' || key === 'right') {
            myPlayer.frame = 0; myPlayer.stepCounter = 0;
            this.game.network.send({ type: "MOVE", x: myPlayer.x, y: myPlayer.y, map: this.game.currentMapName, direction: myPlayer.direction, frame: 0 });
        }
    }
    handleChatKeyDown(e) { if (e.key === 'Enter') this.sendChatMessage(); e.stopPropagation(); }
}

// ==========================================
// 5. MAIN GAME ENGINE SYSTEM
// ==========================================
class PokemonGame {
    constructor() {
        this.myId = null; this.players = {}; this.isLoaded = false; this.currentMapName = 'town'; this.gameState = 'FIELD';
        this.network = new NetworkManager(this); this.renderer = new GameRenderer(this); this.input = new InputHandler(this);
        
        // 🎯 [신설] 현재 등판하여 전투 중인 포켓몬 이름을 명시적으로 기록하는 메모리 상태 변수
        this.currentActivePokemonName = ""; 
    }

    start() {
        document.getElementById('auth-container').style.display = 'none'; document.getElementById('myCanvas').style.display = 'block';
        document.getElementById('game-desc').style.display = 'block'; document.getElementById('bag-container').style.display = 'block';
        this.isLoaded = true; this.loop();
    }

    loop() { this.renderer.render(); requestAnimationFrame(() => this.loop()); }

    appendChatMessage(id, msg) {
        const chatBox = document.getElementById('chat-box'); const newMessage = document.createElement('div');
        newMessage.style.marginBottom = '6px'; newMessage.style.padding = '3px 6px'; newMessage.style.borderRadius = '4px'; newMessage.style.background = 'rgba(0,0,0,0.2)';
        newMessage.innerHTML = (id === this.myId) ? `<span style="color: #ffcb05; font-weight: bold;">[${id}]:</span> ${msg}` : `<span style="color: #5ce6e6; font-weight: bold;">[${id}]:</span> ${msg}`;
        chatBox.appendChild(newMessage); chatBox.scrollTop = chatBox.scrollHeight;
        const targetPlayer = this.players[id];
        if (targetPlayer) {
            if (targetPlayer.chatTimeout) clearTimeout(targetPlayer.chatTimeout); targetPlayer.chatMessage = msg;
            targetPlayer.chatTimeout = setTimeout(() => { if (this.players[id]) this.players[id].chatMessage = null; }, 4000);
        }
    }

    enterBattle(data) {
        this.gameState = 'BATTLE'; 
        const myImgEl = document.getElementById('battle-my-img'); const enemyImgEl = document.getElementById('battle-enemy-img');
        
        const cleanedEnemyName = data.pokemonName ? data.pokemonName.trim() : "";
        if (enemyImgEl) {
            enemyImgEl.src = POKEMON_IMAGE_POOL[cleanedEnemyName] || POKEMON_IMAGE_POOL["구구"];
        }
        
        document.getElementById('enemy-name').innerText = `야생의 ${cleanedEnemyName}`;
        document.getElementById('enemy-level').innerText = `Lv.${data.level}`;
        document.getElementById('enemy-hp-text').innerText = `HP: ${data.hp}/${data.maxHp}`;
        document.getElementById('enemy-hp-bar').style.width = "100%";
        document.getElementById('enemy-hp-bar').style.backgroundColor = "#00ff00";
        
        // 조우 시 내 첫 선발 포켓몬의 정보를 기본 세팅
        if (data.myPokemonHp !== undefined && data.myPokemonMaxHp !== undefined) {
            const myHpBar = document.getElementById('my-pokemon-hp-bar'); const myHpText = document.getElementById('my-pokemon-hp-text');
            const myRatio = (data.myPokemonHp / data.myPokemonMaxHp) * 100;
            myHpBar.style.width = `${myRatio}%`; myHpText.innerText = `HP: ${data.myPokemonHp}/${data.myPokemonMaxHp}`;
            myHpBar.style.backgroundColor = myRatio < 30 ? "#ff5353" : myRatio < 60 ? "#ffcb05" : "#00ff00";
        }

        // 🚨 조우 시점에는 아직 어떤 포켓몬이 0번인지 알 수 없으므로 가방 리스트를 호출하되, 이름 추적 상태는 초기화
        this.currentActivePokemonName = "";
        requestMyBag();

        document.getElementById('battle-log').innerText = `앗! 풀숲에서 야생의 [${cleanedEnemyName}](이)가 튀어나왔다! \n무엇을 할까?`;
        document.getElementById('battle-container').style.display = 'flex';
    }

    exitBattle() { document.getElementById('battle-container').style.display = 'none'; this.gameState = 'FIELD'; }

    updateBagUI(pokemonList) {
        const bagListDiv = document.getElementById('bag-list'); if (pokemonList.length === 0) { bagListDiv.innerHTML = "<span style='color:#aaa;'>소지한 포켓몬이 없습니다.</span>"; return; }
        let htmlContent = "";
        pokemonList.forEach((poke, index) => {
            htmlContent += `<div style="margin-bottom:6px; padding:6px; border-bottom:1px solid #333; background: rgba(0,0,0,0.15); border-radius: 4px; display: flex; justify-content: space-between;">
                <span><strong>[${index + 1}] ${poke.pokemonName}</strong> <span style="color:#aaa; font-size:11px;">Lv.${poke.level}</span></span>
                <span style="color:#ff5353; font-weight:bold;">❤️ HP ${poke.currentHp}/${poke.maxHp}</span>
            </div>`;
        });
        bagListDiv.innerHTML = htmlContent;

        // 🚨 [꼬임 버그 해결의 열쇠]: 
        // 만약 서버가 명시적으로 '누구로 교체해라'라고 명시해주지 않은 첫 조우 시점에만 가방의 0번 인덱스 이름을 가져옵니다.
        if (this.gameState === 'BATTLE' && pokemonList.length > 0) {
            if (!this.currentActivePokemonName) {
                this.currentActivePokemonName = pokemonList[0].pokemonName.trim();
            }
            this.syncActivePokemonUI();
        }
    }

    // 🎯 [독립 렌더링 함수]: 가방 순서 리스트와 무관하게, 현재 지정된 포켓몬 이름으로만 스킨을 그립니다!
    syncActivePokemonUI() {
        if (!this.currentActivePokemonName) return;
        const targetName = this.currentActivePokemonName;

        const myPokeNameEl = document.getElementById('my-pokemon-name');
        if (myPokeNameEl) {
            myPokeNameEl.innerText = `나의 ${targetName}`;
        } else {
            const label = document.querySelector('#battle-container div style div span');
            if (label && label.innerText.includes("나의")) {
                label.innerText = `나의 ${targetName} :`;
            }
        }

        const myImgEl = document.getElementById('battle-my-img');
        if (myImgEl) {
            myImgEl.src = POKEMON_BACK_IMAGE_POOL[targetName] || POKEMON_BACK_IMAGE_POOL["피카츄"];
        }
    }

    updateBattleChangeZone(pokemonList) {
        const zone = document.getElementById('battle-pokemon-list');
        if (!zone) return;
        if (pokemonList.length <= 1) {
            zone.innerHTML = "<span style='color:#aaa; font-size:11px; text-align:center;'>교체할 다른 포켓몬이 없습니다.</span>";
            return;
        }

        let html = "";
        pokemonList.forEach((poke) => {
            // 🚨 현재 필드에 버젓이 등판해 있는 포켓몬은 대기실 버튼 목록에서 제외합니다!
            if (poke.pokemonName.trim() !== this.currentActivePokemonName) {
                const isFainted = poke.currentHp <= 0;
                html += `<button onclick="requestSwitchPokemon(${poke.uniqueId})" ${isFainted ? 'disabled' : ''} style="width: 100%; text-align: left; padding: 5px; font-size: 11px; font-weight: bold; background: ${isFainted ? '#444' : '#34495e'}; color: ${isFainted ? '#aaa' : '#fff'}; border: 1px solid #2c3e50; border-radius: 4px; cursor: ${isFainted ? 'not-allowed' : 'pointer'}; display: flex; justify-content: space-between;">
                    <span>포켓몬: ${poke.pokemonName} (Lv.${poke.level})</span>
                    <span style="color: ${isFainted ? '#ff5353' : '#2ecc71'};">${isFainted ? '💀 기절함' : '❤️ HP ' + poke.currentHp + '/' + poke.maxHp}</span>
                </button>`;
            }
        });
        zone.innerHTML = html;
    }

    handleBattleRound(data) {
        document.getElementById('battle-log').innerText = data.log;
        
        if (data.enemyHp !== undefined) {
            const hpBar = document.getElementById('enemy-hp-bar'); const hpText = document.getElementById('enemy-hp-text');
            const currentMax = parseInt(hpText.innerText.split('/')[1]); const ratio = (data.enemyHp / currentMax) * 100;
            hpBar.style.width = `${ratio}%`; hpText.innerText = `HP: ${data.enemyHp}/${currentMax}`; hpBar.style.backgroundColor = ratio < 30 ? "#ff5353" : "#00ff00"; 
        }
        
        if (data.myPokemonHp !== undefined && data.myPokemonMaxHp !== undefined) {
            const myHpBar = document.getElementById('my-pokemon-hp-bar'); const myHpText = document.getElementById('my-pokemon-hp-text');
            const myRatio = (data.myPokemonHp / data.myPokemonMaxHp) * 100;
            myHpBar.style.width = `${myRatio}%`; myHpText.innerText = `HP: ${data.myPokemonHp}/${data.myPokemonMaxHp}`;
            myHpBar.style.backgroundColor = myRatio < 30 ? "#ff5353" : myRatio < 60 ? "#ffcb05" : "#00ff00";
        }

        // 🚨 [동기화 마스터 피스]: 교체든 공격이든 서버가 "지금 활성화된 아군 이름은 이거야"라고 던져주면 무조건 그 이름으로 고정시킵니다!
        if (data.myPokemonName) {
            this.currentActivePokemonName = data.myPokemonName.trim();
            this.syncActivePokemonUI();
        }

        if (data.log && data.log.includes("[교체]")) {
            this.gameState = 'BATTLE';
            window.focus();
            requestMyBag(); // 교체실 목록 동기화 리로드
        }

        if (data.battleEnded) { setTimeout(() => { this.exitBattle(); if (typeof requestMyBag === 'function') requestMyBag(); }, 2500); }
    }
}

// ==========================================
// 6. GLOBAL ACTION ROUTERS
// ==========================================
const gameInstance = new PokemonGame();
function requestSignup() {
    const u = document.getElementById('username').value.trim(); const p = document.getElementById('password').value.trim();
    if (!u || !p) return alert('아이디와 비밀번호를 모두 입력해주세요!');
    gameInstance.network.send({ type: "SIGNUP", username: u, password: p });
}
function requestLogin() {
    const u = document.getElementById('username').value.trim(); const p = document.getElementById('password').value.trim();
    if (!u || !p) return alert('아이디와 비밀번호를 모두 입력해주세요!');
    gameInstance.network.send({ type: "LOGIN", username: u, password: p });
}
if (typeof window !== 'undefined') { window.requestMyBag = () => gameInstance.network.send({ type: "GET_BAG" }); }
function requestMyBag() { gameInstance.network.send({ type: "GET_BAG" }); }
function clickAttack() { gameInstance.network.send({ type: "BATTLE_ATTACK" }); }
function clickCatch() { gameInstance.network.send({ type: "BATTLE_CATCH" }); }
function clickRunaway() { gameInstance.network.send({ type: "BATTLE_RUN" }); }
function clickSendChat() { gameInstance.input.sendChatMessage(); }

function requestSwitchPokemon(id) {
    gameInstance.network.send({ type: "BATTLE_SWITCH", pokemonId: id });
}