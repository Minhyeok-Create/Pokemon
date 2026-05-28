// ==========================================
// [MODULE 3] NETWORK, INPUT & MAIN ENGINE
// ==========================================
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
        else if (data.type === 'PVP_INVITE_FORWARD') {
            // 다른 트레이너가 나에게 보낸 도전장 패킷 수신 시 모달 소환!
            const modal = document.getElementById('pvp-request-modal');
            const txt = document.getElementById('pvp-request-text');
            if (modal && txt) {
                // 수락/거절 시 백엔드 방 대조를 위해 데이터셋 임시 기록
                modal.dataset.roomId = data.roomId;
                txt.innerHTML = `<span style="color:#ffcb05; font-size:15px;">[${data.senderUserId}]</span> 트레이너가<br>당신에게 진검승부를 신청했습니다!`;
                modal.style.display = 'flex';
            }
        }
        else if (data.type === 'PVP_START') {
            // 🚨 대망의 유저 대인전 멀티 배틀필드 동시 개막!
            this.game.gameState = 'BATTLE'; 
            this.game.pvpRoomId = data.roomId; // 고유 PvP 룸 번호 동기화 바인딩

            // UI 텍스트 초기화 (나와 적 ID 설정)
            document.getElementById('enemy-name').innerText = `💥 [TR] ${data.enemyId}`;
            document.getElementById('enemy-level').innerText = `Lv.${data.enemyPokeLvl}`;
            document.getElementById('enemy-hp-text').innerText = `HP: ${data.enemyPokeHp}/${data.enemyPokeMaxHp}`;
            document.getElementById('enemy-hp-bar').style.width = "100%";
            document.getElementById('enemy-hp-bar').style.backgroundColor = "#00ff00";

            document.getElementById('my-pokemon-name').innerText = `나의 ${data.myPokeName}`;
            document.getElementById('my-pokemon-level').innerText = `Lv.${data.myPokeLvl}`;
            document.getElementById('my-pokemon-hp-text').innerText = `HP: ${data.myPokeHp}/${data.myPokeMaxHp}`;
            
            const myRatio = (data.myPokeHp / data.myPokeMaxHp) * 100;
            document.getElementById('my-pokemon-hp-bar').style.width = `${myRatio}%`;
            document.getElementById('my-pokemon-hp-bar').style.backgroundColor = myRatio < 30 ? "#ff5353" : "#00ff00";

            // 고화질 도감 미러링 렌더링
            document.getElementById('battle-my-img').src = POKEMON_BACK_IMAGE_POOL[data.myPokeName] || POKEMON_BACK_IMAGE_POOL["피카츄"];
            document.getElementById('battle-enemy-img').src = POKEMON_IMAGE_POOL[data.enemyPokeName] || POKEMON_IMAGE_POOL["구구"];

            // 🛑 대인전 핵심 밸런스 가드: 타 트레이너의 포켓몬은 "포획"할 수 없게 하드록 방어!
            const catchBtn = document.getElementById('btn-catch-control');
            if (catchBtn) {
                catchBtn.disabled = true;
                catchBtn.style.opacity = "0.3";
                catchBtn.style.cursor = "not-allowed";
            }

            document.getElementById('battle-log').innerText = `⚔️ 아레나 개막! 상대 트레이너 [${data.enemyId}]과의 실시간 진검승부가 시작되었습니다!\n명령을 내리세요!`;
            document.getElementById('battle-container').style.display = 'flex';
        }
    }

    send(data) {
        if (this.socket.readyState === WebSocket.OPEN) this.socket.send(JSON.stringify(data));
    }
}

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
        const canvasEl = document.getElementById('myCanvas');
        if (canvasEl) {
            canvasEl.addEventListener('mousedown', (e) => {
                // 배틀 중이거나 로드가 안 되었다면 조준 스킵
                if (this.game.gameState === 'BATTLE' || !this.game.isLoaded) return;

                // 캔버스 내에서의 순수 마우스 상대 좌표 계산
                const rect = canvasEl.getBoundingClientRect();
                const clickX = e.clientX - rect.left;
                const clickY = e.clientY - rect.top;

                console.log(`🎯 [캔버스 클릭] X: ${clickX}, Y: ${clickY}`);

                let isTargetFound = false;

                // 현재 게임에 접속 중인 모든 플레이어 목록을 순회하며 히트박스 충돌 검사
                for (let id in this.game.players) {
                    // 자기 자신은 결투 신청 대상에서 제외
                    if (id === this.game.myId) continue;

                    const p = this.game.players[id];
                    // 나랑 다른 맵에 있는 유저라면 스킵
                    if (p.map && p.map !== this.game.currentMapName) continue;

                    // 💡 캐릭터의 타일 기준 히트박스 영역 계산 (80x80 범위)
                    const pLeft = p.x;
                    const pRight = p.x + TILE_SIZE;
                    const pTop = p.y;
                    const pBottom = p.y + TILE_SIZE;

                    // 마우스 클릭 위치가 해당 유저의 캐릭터 영역 안쪽인지 정밀 대조
                    if (clickX >= pLeft && clickX <= pRight && clickY >= pTop && clickY <= pBottom) {
                        console.log(`⚔️ [타겟 조준 완료] 유저 ID: ${id}`);
                        
                        currentTargetUserId = id;
                        isTargetFound = true;

                        // UI 플로팅 버튼 소환 및 머리 위 좌표 레이아웃 정렬
                        const ticket = document.getElementById('pvp-invite-ticket');
                        const label = document.getElementById('target-player-label');
                        
                        if (ticket && label) {
                            label.innerText = `[${id}] 트레이너`;
                            
                            // 캔버스 엘리먼트 위치 기준으로 절대 좌표 오프셋 부여
                            ticket.style.left = `${rect.left + window.scrollX + p.x - 20}px`;
                            ticket.style.top = `${rect.top + window.scrollY + p.y - 45}px`; // 머리 위에 이쁘게 안착
                            ticket.style.display = 'flex';
                        }
                        break;
                    }
                }

                // 빈 땅을 클릭했다면 조준 해제하고 티켓 숨기기
                if (!isTargetFound) {
                    closePvPInviteTicket();
                }
            });
        }
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
            
            if (this.game.currentMapName === 'town' && targetX > canvas.width - CHAR_SIZE) { 
                sendMapName = 'field'; targetX = 40; 
            }
            else if (this.game.currentMapName === 'field' && targetX < 0) { 
                sendMapName = 'town'; targetX = canvas.width - CHAR_SIZE - 40; 
            }
            else if (this.game.currentMapName === 'field' && targetX > canvas.width - CHAR_SIZE) {
                sendMapName = 'dungeon'; targetX = 40; targetY = 160; 
            }
            else if (this.game.currentMapName === 'dungeon' && targetX < 0) {
                sendMapName = 'field'; targetX = canvas.width - CHAR_SIZE - 40;
            }
            else {
                if (targetX < 0) targetX = 0; if (targetX > canvas.width - CHAR_SIZE) targetX = canvas.width - CHAR_SIZE;
                if (targetY < 0) targetY = 0; if (targetY > canvas.height - CHAR_SIZE) targetY = canvas.height - CHAR_SIZE;
            }

            const currentMap = MAPS[sendMapName] || MAPS['town'];
            const gridC = Math.floor((targetX + CHAR_SIZE/2) / TILE_SIZE); const gridR = Math.floor((targetY + CHAR_SIZE/2) / TILE_SIZE);
            
            if (gridR >= 0 && gridR < 5 && gridC >= 0 && gridC < 5) {
                if (currentMap[gridR][gridC] === 3 && sendMapName === 'town') {
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

class PokemonGame {
    constructor() {
        this.myId = null; this.players = {}; this.isLoaded = false; this.currentMapName = 'town'; this.gameState = 'FIELD';
        this.network = new NetworkManager(this); this.renderer = new GameRenderer(this); this.input = new InputHandler(this);
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
        
        if (data.myPokemonHp !== undefined && data.myPokemonMaxHp !== undefined) {
            const myHpBar = document.getElementById('my-pokemon-hp-bar'); const myHpText = document.getElementById('my-pokemon-hp-text');
            const myRatio = (data.myPokemonHp / data.myPokemonMaxHp) * 100;
            myHpBar.style.width = `${myRatio}%`; myHpText.innerText = `HP: ${data.myPokemonHp}/${data.myPokemonMaxHp}`;
            myHpBar.style.backgroundColor = myRatio < 30 ? "#ff5353" : myRatio < 60 ? "#ffcb05" : "#00ff00";
        }

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

        if (this.gameState === 'BATTLE' && pokemonList.length > 0) {
            if (!this.currentActivePokemonName) {
                this.currentActivePokemonName = pokemonList[0].pokemonName.trim();
            }
            this.syncActivePokemonUI();
        }
    }

    syncActivePokemonUI() {
        if (!this.currentActivePokemonName) return;
        const targetName = this.currentActivePokemonName;

        const myPokeNameEl = document.getElementById('my-pokemon-name');
        if (myPokeNameEl) {
            myPokeNameEl.innerText = `나의 ${targetName}`;
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

        if (data.myPokemonName) {
            this.currentActivePokemonName = data.myPokemonName.trim();
            this.syncActivePokemonUI();
        }

        if (data.log && data.log.includes("[교체]")) {
            this.gameState = 'BATTLE';
            window.focus();
            requestMyBag(); 
        }

        if (data.battleEnded) { setTimeout(() => { this.exitBattle(); if (typeof requestMyBag === 'function') requestMyBag(); }, 2500); }
    }
}

// Global action routers
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
function requestSwitchPokemon(id) { gameInstance.network.send({ type: "BATTLE_SWITCH", pokemonId: id }); }
let currentTargetUserId = null; // 현재 내가 조준한 상대방 ID 보관소

function clickSendPvPInvite() {
    if (!currentTargetUserId) return;
    console.log(`📡 [PvP 초대 송신] -> 대상: ${currentTargetUserId}`);
    
    // 백엔드로 정밀 조준 도전장 패킷 사출! (2단계에서 백엔드가 수신할 예정)
    gameInstance.network.send({ 
        type: "PVP_INVITE", 
        targetUserId: currentTargetUserId 
    });
    
    // 신청 후 티켓 박스는 깔끔하게 닫기
    closePvPInviteTicket();
}
function closePvPInviteTicket() {
    const ticket = document.getElementById('pvp-invite-ticket');
    if (ticket) ticket.style.display = 'none';
    currentTargetUserId = null;
}
function clickPvPResponse(isAccept) {
    const modal = document.getElementById('pvp-request-modal');
    if (!modal || !modal.dataset.roomId) return;

    const roomId = modal.dataset.roomId;
    const actionType = isAccept ? "ACCEPT" : "REJECT";

    console.log(`📡 [PvP 응답 송신] -> 방장룸: ${roomId}, 선택: ${actionType}`);

    // 백엔드로 결정 사항 보석 봉인 팩 사출!
    gameInstance.network.send({
        type: "PVP_RESPONSE",
        roomId: roomId,
        action: actionType
    });

    // 팝업창 닫기 및 데이터 리셋
    modal.style.display = 'none';
    modal.removeAttribute('data-room-id');
}

// 🚨 야생전 종료와 PvP 대전 종료 공용 처리를 위해 exitBattle 로직 안전 보완
const originalExitBattle = PokemonGame.prototype.exitBattle;
PokemonGame.prototype.exitBattle = function() {
    // 본래의 필드 스위칭 로직 발동
    originalExitBattle.call(this);
    
    // PvP 전투 룸 바인딩 ID 삭제 및 포획 금지 락 해제
    this.pvpRoomId = null;
    const catchBtn = document.getElementById('btn-catch-control');
    if (catchBtn) {
        catchBtn.disabled = false;
        catchBtn.style.opacity = "1";
        catchBtn.style.cursor = "pointer";
    }
};
