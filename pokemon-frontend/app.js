// ==========================================
// [MODULE 3] NETWORK, INPUT & MAIN ENGINE
// ==========================================
class NetworkManager {
    constructor(game) {
        this.game = game;
        this.pendingRequest = null; 
        this.isConnecting = false; 
        this.connect(); 
    }

    connect() {
        if (this.isConnecting) return;
        this.isConnecting = true;

        console.log('📡 서버 연결 시도 중...');
        this.socket = new WebSocket(SERVER_URL);

        this.socket.onopen = () => { 
            console.log('📡 WebSocket 연결 성공! 서버가 깨어났습니다.', SERVER_URL); 
            this.isConnecting = false;
            
            if (this.pendingRequest) {
                console.log("🚀 [자동 인지] 예약된 요청을 자동으로 전송합니다:", this.pendingRequest);
                this.socket.send(JSON.stringify(this.pendingRequest));
                this.pendingRequest = null; 
            }
        };
        
        this.socket.onmessage = (event) => this.handleMessage(JSON.parse(event.data));

        this.socket.onclose = () => {
            this.isConnecting = false;
            if (this.game.gameState !== 'BATTLE' && !this.game.isLoaded) {
                console.log('💤 서버가 잠들어 있습니다. 1.5초 후 다시 깨우러 갑니다...');
                setTimeout(() => this.connect(), 1500);
            }
        };

        this.socket.onerror = (err) => {
            console.log('❌ 소켓 연결 지연 발생 (서버 켜지는 중)');
        };
    }

    handleMessage(data) {
        console.log("📥 수신 패킷:", data);

        if (data.type === 'SIGNUP_RESULT') {
            const loadingNotice = document.getElementById('server-loading-notice');
            if (loadingNotice) loadingNotice.style.display = 'none';

            alert(data.message);
            if (data.success) document.getElementById('password').value = '';
        }
        else if (data.type === 'LOGIN_RESULT') {
            const loadingNotice = document.getElementById('server-loading-notice');
            if (loadingNotice) loadingNotice.style.display = 'none';

            if (!data.success) alert(data.message);
        }
        else if (data.type === 'INIT') {
            this.game.myId = data.myId;
            
            // 초기 접속 유저 데이터 세팅
            this.game.players = {};
            for (let id in data.players) {
                const pData = data.players[id];
                this.game.players[id] = {
                    x: pData.x,
                    y: pData.y,
                    targetX: pData.x, // 🌟 보간용 목적지 X 초기화
                    targetY: pData.y, // 🌟 보간용 목적지 Y 초기화
                    map: pData.map ? pData.map.toLowerCase() : 'town',
                    direction: 'down',
                    frame: 0,
                    stepCounter: 0
                };
            }
            
            this.game.start();
            this.game.updateSignpost(); 
        }
        else if (data.type === 'UPDATE') {
            const safeMapName = data.map ? data.map.toLowerCase() : 'town';
            
            if (this.game.players[data.id]) {
                // 🌟 [핵심 변경]: 내 캐릭터가 아닐 때만 부드러운 목적지 보간 추적기 가동
                if (data.id === this.game.myId) {
                    this.game.players[data.id].x = data.x;
                    this.game.players[data.id].y = data.y;
                } else {
                    // 타 유저라면 좌표를 팍 바꾸지 않고 목적지만 갱신하여 미끄러지듯 이동하게 유도
                    this.game.players[data.id].targetX = data.x;
                    this.game.players[data.id].targetY = data.y;
                }
                
                this.game.players[data.id].map = safeMapName;
                if (data.direction) this.game.players[data.id].direction = data.direction;
                this.game.players[data.id].frame = data.frame !== undefined ? data.frame : 0;
            } else {
                // 신규 진입 유저 생성
                this.game.players[data.id] = { 
                    x: data.x, 
                    y: data.y, 
                    targetX: data.x,
                    targetY: data.y,
                    map: safeMapName, 
                    direction: data.direction || 'down', 
                    frame: 0 
                };
            }
            
            if (data.id === this.game.myId) {
                this.game.currentMapName = safeMapName;
                this.game.updateSignpost(); 
            }
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
            console.log("🌾 야생 포켓몬 발견! 선발 대기 스크린 정렬 시작");
            this.game.lastEncounteredEnemy = data; 
            this.send({ type: "GET_BAG" });        
            this.game.waitingForStarterSelect = true; 
        }
        else if (data.type === 'WILD_BATTLE_OPEN_FIELD') {
            document.getElementById('starter-select-modal').style.display = 'none';
            this.game.gameState = 'BATTLE';

            document.getElementById('enemy-name').innerText = `야생의 ${data.pokemonName}`;
            document.getElementById('enemy-level').innerText = `Lv.${data.level}`;
            document.getElementById('enemy-hp-text').innerText = `HP: ${data.hp}/${data.maxHp}`;
            document.getElementById('enemy-hp-bar').style.width = "100%";
            document.getElementById('enemy-hp-bar').style.backgroundColor = "#00ff00";

            document.getElementById('my-pokemon-name').innerText = `나의 ${data.myActivePokeName}`;
            document.getElementById('my-pokemon-level').innerText = `Lv.${data.myActivePokeLvl}`;
            document.getElementById('my-pokemon-hp-text').innerText = `HP: ${data.myPokemonHp}/${data.myPokemonMaxHp}`;
            
            const myRatio = (data.myPokemonHp / data.myPokemonMaxHp) * 100;
            document.getElementById('my-pokemon-hp-bar').style.width = `${myRatio}%`;
            document.getElementById('my-pokemon-hp-bar').style.backgroundColor = myRatio < 30 ? "#ff5353" : "#00ff00";

            document.getElementById('battle-my-img').src = POKEMON_BACK_IMAGE_POOL[data.myActivePokeName] || POKEMON_BACK_IMAGE_POOL["피카츄"];
            document.getElementById('battle-enemy-img').src = POKEMON_IMAGE_POOL[data.pokemonName] || POKEMON_IMAGE_POOL["구구"];

            document.getElementById('battle-log').innerText = `🌾 야생의 ${data.pokemonName}(이)가 덤벼들었다!\n가라, ${data.myActivePokeName}!!!`;
            document.getElementById('battle-container').style.display = 'flex';
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
            const modal = document.getElementById('pvp-request-modal');
            const txt = document.getElementById('pvp-request-text');
            if (modal && txt) {
                modal.dataset.roomId = data.roomId;
                txt.innerHTML = `<span style="color:#ffcb05; font-size:15px;">[${data.senderUserId}]</span> 트레이너가<br>당신에게 진검승부를 신청했습니다!`;
                modal.style.display = 'flex';
            }
        }
        else if (data.type === 'PVP_INVITE_ACCEPTED_CHOOSE_STARTER') {
            console.log("⚔️ PvP 결투 성사! 선발 출전몬 지목 팝업 가동");
            this.send({ type: "GET_BAG" });
            this.game.waitingForStarterSelect = true;
        }
        else if (data.type === 'PVP_START') {
            const selectModal = document.getElementById('starter-select-modal');
            if (selectModal) { selectModal.style.display = 'none'; }

            this.game.gameState = 'BATTLE'; 
            this.game.pvpRoomId = data.roomId; 

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

            document.getElementById('battle-my-img').src = POKEMON_BACK_IMAGE_POOL[data.myPokeName] || POKEMON_BACK_IMAGE_POOL["피카츄"];
            document.getElementById('battle-enemy-img').src = POKEMON_IMAGE_POOL[data.enemyPokeName] || POKEMON_IMAGE_POOL["구구"];

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
        if (this.socket && this.socket.readyState === WebSocket.OPEN) {
            this.socket.send(JSON.stringify(data));
        } else {
            console.log("💤 서버가 잠들어 있어 자동 사출을 예약 등록합니다:", data);
            this.pendingRequest = data; 
        }
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
                if (this.game.gameState === 'BATTLE' || !this.game.isLoaded) return;

                const rect = canvasEl.getBoundingClientRect();
                const clickX = e.clientX - rect.left;
                const clickY = e.clientY - rect.top;

                console.log(`🎯 [캔버스 클릭] X: ${clickX}, Y: ${clickY}`);
                let isTargetFound = false;

                for (let id in this.game.players) {
                    if (id === this.game.myId) continue;

                    const p = this.game.players[id];
                    if (p.map && p.map !== this.game.currentMapName) continue;

                    const pLeft = p.x;
                    const pRight = p.x + TILE_SIZE;
                    const pTop = p.y;
                    const pBottom = p.y + TILE_SIZE;

                    if (clickX >= pLeft && clickX <= pRight && clickY >= pTop && clickY <= pBottom) {
                        console.log("⚔️ [타겟 조준 완료] 유저 ID:", id);
                        
                        currentTargetUserId = id;
                        isTargetFound = true;

                        const ticket = document.getElementById('pvp-invite-ticket');
                        const label = document.getElementById('target-player-label');
                        
                        if (ticket && label) {
                            label.innerText = `[${id}] 트레이너`;
                            ticket.style.left = `${rect.left + window.scrollX + p.x - 20}px`;
                            ticket.style.top = `${rect.top + window.scrollY + p.y - 45}px`; 
                            ticket.style.display = 'flex';
                        }
                        break;
                    }
                }

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
            
            let portalTriggered = false; // 🚨 포탈 이동 감지 플래그

            if (this.game.currentMapName === 'town' && targetX > canvas.width - CHAR_SIZE) { 
                sendMapName = 'field'; targetX = 40; portalTriggered = true;
            }
            else if (this.game.currentMapName === 'field' && targetX < 0) { 
                sendMapName = 'town'; targetX = canvas.width - CHAR_SIZE - 40; portalTriggered = true;
            }
            else if (this.game.currentMapName === 'field' && targetX > canvas.width - CHAR_SIZE) {
                sendMapName = 'dungeon'; targetX = 40; targetY = 160; portalTriggered = true;
            }
            else if (this.game.currentMapName === 'dungeon' && targetX < 0) {
                sendMapName = 'field'; targetX = canvas.width - CHAR_SIZE - 40; portalTriggered = true;
            }
            else {
                if (targetX < 0) targetX = 0; if (targetX > canvas.width - CHAR_SIZE) targetX = canvas.width - CHAR_SIZE;
                if (targetY < 0) targetY = 0; if (targetY > canvas.height - CHAR_SIZE) targetY = canvas.height - CHAR_SIZE;
            }

            // 🚨 [수리 포인트 2]: 포탈을 타고 대륙 이동이 일어난 순간에는 보간 버퍼와 실제 좌표를 칼같이 일치시킵니다.
            if (portalTriggered) {
                myPlayer.x = targetX;
                myPlayer.y = targetY;
                myPlayer.targetX = targetX;
                myPlayer.targetY = targetY;
                this.game.currentMapName = sendMapName; // 맵 컨텍스트 즉시 선행 반영
            } else {
                // 일반 이동일 때는 목적지만 업데이트
                myPlayer.x = targetX;
                myPlayer.y = targetY;
                myPlayer.targetX = targetX;
                myPlayer.targetY = targetY;
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
        this.lastEncounteredEnemy = null;       
        this.waitingForStarterSelect = false;   
    }

    start() {
        document.getElementById('auth-container').style.display = 'none'; document.getElementById('myCanvas').style.display = 'block';
        document.getElementById('game-desc').style.display = 'block'; document.getElementById('bag-container').style.display = 'block';
        this.isLoaded = true; this.loop();
    }


    loop() { 
        for (let id in this.players) {
            const p = this.players[id];
            
            //  [내 캐릭터는 클라이언트 예측 무빙이므로 보간 연산에서 제외
            if (id === this.myId) {
                // 내 캐릭터는 목적지 좌표 버퍼를 실제 좌표와 실시간 동기화만 시켜둡니다.
                p.targetX = p.x;
                p.targetY = p.y;
                continue; 
            }
            
            if (p.targetX === undefined) p.targetX = p.x;
            if (p.targetY === undefined) p.targetY = p.y;
            
            // 타 유저 캐릭터만 이동 보정 (속도 살짝 상향 0.20 -> 0.25)
            p.x += (p.targetX - p.x) * 0.25;
            p.y += (p.targetY - p.y) * 0.25;
        }

        this.renderer.render(); 
        requestAnimationFrame(() => this.loop()); 
    }

    updateSignpost() {
        const mapLabel = document.getElementById('current-map-label');
        const arrowLabel = document.getElementById('next-map-arrow');
        if (!mapLabel || !arrowLabel) return;

        if (this.currentMapName === 'town') {
            mapLabel.innerText = "태초 마을 (town)";
            arrowLabel.innerText = "➡️ 오른쪽 관문 탈출: 들판(field) 진입";
            arrowLabel.style.color = "#2ecc71";
        } else if (this.currentMapName === 'field') {
            mapLabel.innerText = "숨겨진 들판 (field)";
            arrowLabel.innerText = "⬅️ 좌측: 마을 복귀 | ➡️ 우측 최하단: 용의 굴 던전 개방";
            arrowLabel.style.color = "#f1c40f";
        } else if (this.currentMapName === 'dungeon') {
            mapLabel.innerText = "🌋 전설의 용의 굴 (dungeon)";
            arrowLabel.innerText = "⬅️ 왼쪽 끝 탈출: 들판(field)으로 무사 복귀";
            arrowLabel.style.color = "#e74c3c";
        }
    }

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

    exitBattle() { document.getElementById('battle-container').style.display = 'none'; this.gameState = 'FIELD'; }

    updateBagUI(pokemonList) {
        const bagListDiv = document.getElementById('bag-list'); 
        if (pokemonList.length === 0) { 
            bagListDiv.innerHTML = "<span style='color:#aaa;'>소지한 포켓몬이 없습니다.</span>"; 
            return; 
        }
        
        let htmlContent = "";
        pokemonList.forEach((poke, index) => {
            const isFainted = poke.currentHp <= 0;
            const statusText = isFainted ? `<span style="color:#ff5353; font-weight:bold;">[기절]</span>` : `<span style="color:#2ecc71; font-weight:bold;">(${poke.currentHp}/${poke.maxHp})</span>`;
            
            htmlContent += `
                <div style="margin-bottom:6px; padding:6px; border-bottom:1px solid #333; background: rgba(0,0,0,0.15); border-radius: 4px; display: flex; justify-content: space-between; align-items:center;">
                    <span><strong>[${index + 1}] ${poke.pokemonName}</strong> <span style="color:#aaa; font-size:11px;">Lv.${poke.level}</span><br>${statusText}</span>
                    <button onclick="clickReleasePokemon(${poke.uniqueId}, '${poke.pokemonName}')" style="background:#e74c3c; color:#fff; border:none; padding:4px 6px; border-radius:4px; font-size:10px; cursor:pointer;">🍂방생</button>
                </div>`;
        });
        bagListDiv.innerHTML = htmlContent;

        if (this.gameState === 'BATTLE' && pokemonList.length > 0) {
            if (!this.currentActivePokemonName) {
                this.currentActivePokemonName = pokemonList[0].pokemonName.trim();
            }
            this.syncActivePokemonUI();
        }

        if (this.waitingForStarterSelect) {
            this.waitingForStarterSelect = false; 
            const selectModal = document.getElementById('starter-select-modal');
            const listBox = document.getElementById('starter-pokemon-list-box');
            
            const modalTitle = document.querySelector('#starter-select-modal h3');
            if (modalTitle) {
                modalTitle.innerText = this.pvpRoomId || this.network.game.players[this.network.game.myId].roomId 
                    ? "⚔️ 실시간 PvP 트레이너 대전" 
                    : "🌾 야생 포켓몬과 조우했습니다!";
            }

            if (selectModal && listBox) {
                listBox.innerHTML = '';
                pokemonList.forEach(poke => {
                    const isFainted = poke.currentHp <= 0;
                    const row = document.createElement('div');
                    
                    row.id = `starter-row-${poke.uniqueId}`; 
                    row.style = `padding:10px; border-radius:6px; font-size:12px; text-align:left; cursor:${isFainted ? 'not-allowed':'pointer'}; background:${isFainted ? '#555':'#2c3e50'}; color:#fff; border: 1px solid ${isFainted ? '#333':'#3498db'}; display:flex; justify-content:space-between; margin-bottom:4px; transition: 0.2s;`;
                    
                    if (!isFainted) {
                        row.onclick = () => {
                            console.log(`📡 선발 투수 확정 전송: ${poke.pokemonName} (${poke.uniqueId})`);
                            
                            row.style.background = "#1abc9c";
                            row.style.borderColor = "#ffffff";
                            row.style.transform = "scale(0.97)";
                            row.style.pointerEvents = "none"; 

                            const statusSpan = row.querySelector('.status-span');
                            if (statusSpan) statusSpan.innerHTML = "⌛ 출격 대기 중...";

                            this.network.send({ type: "BATTLE_START_CONFIRM", pokemonId: poke.uniqueId });
                        };
                    }
                    
                    row.innerHTML = `
                        <span><strong>${poke.pokemonName}</strong> <span style="color:#bdc3c7; font-size:10px;">Lv.${poke.level}</span></span>
                        <span class="status-span" style="color:${isFainted ? '#ff5353':'#2ecc71'}; font-weight:bold;">${isFainted ? '기절' : 'HP ' + poke.currentHp}</span>
                    `;
                    listBox.appendChild(row);
                });
                selectModal.style.display = 'flex'; 
            }
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
                html += `<button onclick="requestSwitchPokemon(${poke.uniqueId})" ${isFainted ? 'disabled' : ''} style="width: 100%; text-align: left; padding: 5px; font-size: 11px; font-weight: bold; background: ${isFainted ? '#444' : '#34495e'}; color: ${isFainted ? '#aaa' : '#fff'}; border: 1px solid #2c3e50; border-radius: 4px; cursor: ${isFainted ? 'not-allowed' : 'pointer'}; display: flex; justify-content: space-between; margin-bottom:4px;">
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
    
    const notice = document.getElementById('server-loading-notice');
    if (notice) notice.style.display = 'block';

    gameInstance.network.send({ type: "SIGNUP", username: u, password: p });
}

function requestLogin() {
    const u = document.getElementById('username').value.trim(); const p = document.getElementById('password').value.trim();
    if (!u || !p) return alert('아이디와 비밀번호를 모두 입력해주세요!');
    
    const notice = document.getElementById('server-loading-notice');
    if (notice) notice.style.display = 'block';

    gameInstance.network.send({ type: "LOGIN", username: u, password: p });
}

if (typeof window !== 'undefined') { window.requestMyBag = () => gameInstance.network.send({ type: "GET_BAG" }); }
function requestMyBag() { gameInstance.network.send({ type: "GET_BAG" }); }
function clickAttack() { gameInstance.network.send({ type: "BATTLE_ATTACK" }); }
function clickCatch() { gameInstance.network.send({ type: "BATTLE_CATCH" }); }
function clickRunaway() { gameInstance.network.send({ type: "BATTLE_RUN" }); }
function clickSendChat() { gameInstance.input.sendChatMessage(); }
function requestSwitchPokemon(id) { gameInstance.network.send({ type: "BATTLE_SWITCH", pokemonId: id }); }

function clickReleasePokemon(uniqueId, name) {
    if (confirm(`🍂 정말로 소중한 동료 [${name}]을(를) 야생 자연의 품으로 방생하시겠습니까?\n삭제된 데이터는 복구할 수 없습니다.`)) {
        console.log(`📡 [방생 요청 사출] -> UniqueID: ${uniqueId}`);
        gameInstance.network.send({
            type: "RELEASE_POKEMON",
            pokemonId: uniqueId
        });
    }
}

let currentTargetUserId = null; 

function clickSendPvPInvite() {
    if (!currentTargetUserId) return;
    console.log(`📡 [PvP 초대 송신] -> 대상: ${currentTargetUserId}`);
    gameInstance.network.send({ 
        type: "PVP_INVITE", 
        targetUserId: currentTargetUserId 
    });
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

    gameInstance.network.send({
        type: "PVP_RESPONSE",
        roomId: roomId,
        action: actionType
    });

    modal.style.display = 'none';
    modal.removeAttribute('data-room-id');
}

const originalExitBattle = PokemonGame.prototype.exitBattle;
PokemonGame.prototype.exitBattle = function() {
    originalExitBattle.call(this);
    
    this.pvpRoomId = null;
    const catchBtn = document.getElementById('btn-catch-control');
    if (catchBtn) {
        catchBtn.disabled = false;
        catchBtn.style.opacity = "1";
        catchBtn.style.cursor = "pointer";
    }
};