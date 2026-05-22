const canvas = document.getElementById('myCanvas');
const ctx = canvas.getContext('2d');

const charimage = new Image();
charimage.src = 'https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/25.png';

let myId = null;       // 서버 로그인 성공 시 나에게 부여해 준 고유 트레이너 ID (아이디)
let players = {};      // 서버에 접속 중인 모든 플레이어들의 좌표 저장소 ({ id: {x, y, map, chatMessage, chatTimeout}, ... })
let isLoaded = false;  // 로그인 완료 전까지 게임 루프 가동 및 키 입력을 잠그는 플래그

// 🗺️ 맵별 타일 데이터 구조 정의
const maps = {
    town: [
        [0, 0, 0, 1, 1],
        [1, 1, 0, 1, 0],
        [0, 0, 0, 0, 0],
        [0, 1, 1, 1, 0],
        [0, 0, 0, 1, 0]
    ],
    field: [
        [0, 0, 0, 0, 0],
        [0, 1, 1, 1, 0],
        [0, 0, 0, 0, 0],
        [0, 1, 0, 1, 0],
        [1, 1, 0, 1, 1]
    ]
};

let currentMapName = 'town'; // 내 화면 캔버스 렌더링 기준 맵 명칭
const TILE_SIZE = 80;
const SPEED = 8;
const CHAR_SIZE = 64;
const CANVAS_WIDTH = canvas.width;
const CANVAS_HEIGHT = canvas.height;

/* 🌐 [배포 대비 핵심 수정] 로컬 환경과 실제 인터넷 배포 환경의 웹소켓 주소를 자동으로 분기합니다. */
const isLocalhost = window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1';
const SERVER_URL = isLocalhost
    ? 'ws://localhost:8080/game'
    : 'wss://pokemon-backend-o9nh.onrender.com/game'; 

const socket = new WebSocket(SERVER_URL);

socket.onopen = () => { console.log('📡 WebSocket 연결 성공 -> 주소:', SERVER_URL); };

// 📡 서버에서 날아오는 방송 종류(type)에 따라 처리 분기
socket.onmessage = (event) => {
    const data = JSON.parse(event.data);
    console.log("📥 서버 수신:", data);

    // 📝 [A] 백엔드로부터 회원가입 결과를 받았을 때
    if (data.type === 'SIGNUP_RESULT') {
        alert(data.message);
        if (data.success) {
            document.getElementById('password').value = ''; // 회원가입 성공 시 비번 칸만 초기화
        }
    }
    
    // 🔑 [B] 백엔드로부터 로그인 결과를 받았을 때 (실패 케이스)
    else if (data.type === 'LOGIN_RESULT') {
        if (!data.success) {
            alert(data.message);
        }
    }

    // 🎮 [C] 로그인 통과 후 서버가 주는 맵 전체 데이터 수신 ("INIT")
    else if (data.type === 'INIT') {
        myId = data.myId;
        players = data.players;
        
        // 내 캐릭터가 등록되어 있고 맵 정보가 있다면 로컬 맵 동기화
        if (players[myId] && players[myId].map) {
            currentMapName = players[myId].map.toLowerCase();
        }
        
        // 💡 UI 대전환: 로그인 화면을 완전히 숨기고, 숨겨져 있던 캔버사와 채팅창을 드러냅니다.
        document.getElementById('auth-container').style.display = 'none';
        document.getElementById('myCanvas').style.display = 'block';
        document.getElementById('game-desc').style.display = 'block';
        document.getElementById('chat-container').style.display = 'block'; // 💬 채팅창 오픈!
        
        // 게임 가동 상태로 변경하고 그래픽 렌더링 시작
        isLoaded = true; 
        gameLoop(); 
        console.log('🎮 게임 접속 완료! 내 캐릭터 ID:', myId);
    }
    
    // 🏃‍♂️ [D] 실시간 이동 및 퇴장 처리
    else if (data.type === 'UPDATE') {
        const safeMapName = data.map ? data.map.toLowerCase() : 'town';
        
        // 🛠️ [버그 수정]: 이동 패킷이 올 때 말풍선(chatMessage)과 타이머(chatTimeout)가 지워지지 않도록 기존 값을 보존합니다.
        if (players[data.id]) {
            players[data.id].x = data.x;
            players[data.id].y = data.y;
            players[data.id].map = safeMapName; // 🗺️ 유저별 현재 소속 맵 갱신
        } else {
            players[data.id] = { x: data.x, y: data.y, map: safeMapName };
        }

        // 💡 실시간으로 내려받은 패킷 주인이 '나'라면 화면 렌더링 타겟 맵 변수도 완전 동기화시킵니다.
        if (data.id === myId) {
            currentMapName = safeMapName;
        }
    } 
    else if (data.type === 'REMOVE') {
        // 메모리 누수 방지: 퇴장한 유저의 타이머가 돌고 있다면 제거
        if (players[data.id] && players[data.id].chatTimeout) {
            clearTimeout(players[data.id].chatTimeout);
        }
        delete players[data.id];
    }

    // 💬 [E] 누군가 채팅을 쳤을 때 채팅창에 실시간으로 추가 + 머리 위 말풍선 연동
    else if (data.type === 'CHAT_MESSAGE') {
        const chatBox = document.getElementById('chat-box');
        const newMessage = document.createElement('div');
        newMessage.style.marginBottom = '5px';
        
        if (data.id === myId) {
            newMessage.innerHTML = `<span style="color: #ffcb05; font-weight: bold;">[${data.id}]:</span> ${data.msg}`;
        } else {
            newMessage.innerHTML = `<span style="color: #5ce6e6; font-weight: bold;">[${data.id}]:</span> ${data.msg}`;
        }
        
        chatBox.appendChild(newMessage);
        chatBox.scrollTop = chatBox.scrollHeight; // 새로운 대화가 오면 스크롤을 맨 아래로 이동

        // 🎈 말풍선 데이터 바인딩 및 4초 타이머 처리
        if (players[data.id]) {
            // 이미 켜져 있는 타이머가 있다면 초기화 (대화를 연달아 치면 시간 초기화 및 연장)
            if (players[data.id].chatTimeout) {
                clearTimeout(players[data.id].chatTimeout);
            }
            
            // 유저 객체에 메시지 텍스트 입력
            players[data.id].chatMessage = data.msg;
            
            // 4초 뒤 말풍선 숨기기
            players[data.id].chatTimeout = setTimeout(() => {
                if (players[data.id]) {
                    players[data.id].chatMessage = null;
                }
            }, 4000);
        }
    }
};

// 📝 [송신] HTML 회원가입 버튼을 눌렀을 때
function requestSignup() {
    const usernameInput = document.getElementById('username').value.trim();
    const passwordInput = document.getElementById('password').value.trim();

    if (!usernameInput || !passwordInput) {
        alert('아이디와 비밀번호를 모두 입력해주세요!');
        return;
    }

    socket.send(JSON.stringify({
        type: "SIGNUP",
        username: usernameInput,
        password: passwordInput
    }));
}

// 🔑 [송신] HTML 로그인 버튼을 눌렀을 때
function requestLogin() {
    const usernameInput = document.getElementById('username').value.trim();
    const passwordInput = document.getElementById('password').value.trim();

    if (!usernameInput || !passwordInput) {
        alert('아이디와 비밀번호를 모두 입력해주세요!');
        return;
    }

    socket.send(JSON.stringify({
        type: "LOGIN",
        username: usernameInput,
        password: passwordInput
    }));
}

// 🎈 캔버스 내 피카츄 머리 위에 말풍선 그래픽을 그려주는 헬퍼 함수
function drawBubble(ctx, text, x, y) {
    ctx.font = '12px sans-serif';
    const textWidth = ctx.measureText(text).width;
    const bubbleWidth = textWidth + 16;  // 여백 확보
    const bubbleHeight = 24;
    
    // 피카츄 스프라이트(64x64) 머리 위 중앙을 맞추기 위한 x, y 좌표 조절
    const bx = x + 32 - (bubbleWidth / 2);
    const by = y - 35;

    // 1. 말풍선 배경 박스 (둥근 사각형)
    ctx.fillStyle = 'rgba(255, 255, 255, 0.9)';
    ctx.beginPath();
    ctx.roundRect(bx, by, bubbleWidth, bubbleHeight, 6);
    ctx.fill();

    // 2. 외곽 테두리선
    ctx.strokeStyle = '#000000';
    ctx.lineWidth = 1;
    ctx.stroke();

    // 3. 아래쪽을 가리키는 작은 삼각형 꼬리 (▼ 모양)
    ctx.fillStyle = 'rgba(255, 255, 255, 0.9)';
    ctx.beginPath();
    ctx.moveTo(x + 32 - 5, by + bubbleHeight);
    ctx.lineTo(x + 32 + 5, by + bubbleHeight);
    ctx.lineTo(x + 32, by + bubbleHeight + 5);
    ctx.closePath();
    ctx.fill();
    ctx.stroke();

    // 4. 대화 내용 글자 인쇄
    ctx.fillStyle = '#000000';
    ctx.textAlign = 'center';
    ctx.fillText(text, bx + (bubbleWidth / 2), by + 16);
    ctx.textAlign = 'left'; // 캔버스 다른 텍스트 정렬에 영향 없도록 폰트 기본값 복구
}

// 🎨 그래픽 렌더링 엔진 루프
function gameLoop() {
    if (!isLoaded) return; 

    // 현재 플레이어가 가리키는 실제 최신 동기화 맵 정보를 기반으로 캐싱 추적
    const currentMap = maps[currentMapName] ? maps[currentMapName] : maps['town'];

    // 🎨 [버그 교정]: 새 프레임을 찍을 때 기존 캔버스 찌꺼기 도화지를 완전히 초기화 클리어 처리합니다.
    ctx.clearRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT);

    // 1. 배경 그리기 (마을은 오리지널 연두색 #7cfc00, 들판은 구분선이 확실히 가도록 사막 황토색 #f4a460 처리)
    ctx.fillStyle = currentMapName === 'town' ? '#7cfc00' : '#f4a460';
    ctx.fillRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT);

    // 2. 벽 그리기
    for (let r = 0; r < 5; r++) {
        for (let c = 0; c < 5; c++) {
            if (currentMap[r][c] === 1) {
                ctx.fillStyle = '#8b4513';
                ctx.fillRect(c * TILE_SIZE, r * TILE_SIZE, TILE_SIZE, TILE_SIZE);
            }
        }
    }

    // 3. 접속 중인 모든 플레이어들의 피카츄 및 아이디 닉네임, 말풍선 그리기
    for (let id in players) {
        const p = players[id];
        
        // 💡 나랑 같은 공간(맵)에 속한 트레이너 유저들만 화면 캔버스에 드로잉 처리하고 다르면 패스!
        if (p.map && p.map !== currentMapName) continue;
        
        ctx.drawImage(charimage, p.x, p.y, 64, 64);
        
        ctx.font = 'bold 12px Arial';
        if (id === myId) {
            ctx.fillStyle = '#0000ff'; 
            ctx.fillText(id + " (Me)", p.x + 10, p.y - 5);
        } else {
            ctx.fillStyle = '#000000'; 
            ctx.fillText(id, p.x + 15, p.y - 5);
        }

        // 🎈 [말풍선 렌더링]: 플레이어 객체에 현재 유효한 chatMessage가 있다면 머리 위에 그립니다.
        if (p.chatMessage) {
            drawBubble(ctx, p.chatMessage, p.x, p.y);
        }
    }

    requestAnimationFrame(gameLoop);
}

// ⌨️ [독립 분리 완료] 키보드 조작 이벤트 제어 리스너 (경계면 안전 마진 워프 적용)
window.addEventListener('keydown', (e) => {
    if (!isLoaded || !myId || !players[myId]) return;

    let targetX = players[myId].x;
    let targetY = players[myId].y;
    let sendMapName = currentMapName; // 현재 내가 서 있는 로컬 맵 기준 매핑 시작
    let moved = false;
   
    if (e.key === 'ArrowUp')    { targetY -= SPEED; moved = true; }
    if (e.key === 'ArrowDown')  { targetY += SPEED; moved = true; }
    if (e.key === 'ArrowLeft')  { targetX -= SPEED; moved = true; }
    if (e.key === 'ArrowRight') { targetX += SPEED; moved = true; }

    if (moved) {
        // 🚪 A. 마을(town) 우측 경계선 탈출 시 -> 들판(field) 안전마진 구역(X=40)으로 스폰 좌표 가공
        if (currentMapName === 'town' && targetX > CANVAS_WIDTH - CHAR_SIZE) {
            sendMapName = 'field';
            targetX = 40; 
        }
        // 🚪 B. 들판(field) 좌측 경계선 탈출 시 -> 마을(town) 안전마진 구역(오른쪽 입구선)으로 리턴 복귀
        else if (currentMapName === 'field' && targetX < 0) {
            sendMapName = 'town';
            targetX = CANVAS_WIDTH - CHAR_SIZE - 40; 
        }
        // 🧱 C. 일반 맵 내부 무빙일 때만 캔버스 테두리 외부 무단 이탈 차단막 기동
        else {
            if (targetX < 0) targetX = 0;
            if (targetX > CANVAS_WIDTH - CHAR_SIZE) targetX = CANVAS_WIDTH - CHAR_SIZE;
            if (targetY < 0) targetY = 0;
            if (targetY > CANVAS_HEIGHT - CHAR_SIZE) targetY = CANVAS_HEIGHT - CHAR_SIZE;
        }

        if (socket.readyState === WebSocket.OPEN) {
            const wishData = { 
                type: "MOVE",
                x: targetX, 
                y: targetY,
                map: sendMapName // 🗺️ 타겟 맵 이름 도장을 함께 찍어서 서버로 상신
            };
            socket.send(JSON.stringify(wishData));
        }
    }
});

// ⌨️ [독립 분리 완료] 채팅 입력창 엔터키 이벤트 리스너
const chatInput = document.getElementById('chat-input');
chatInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
        const msg = chatInput.value.trim();
        if (msg === '') return;

        if (socket.readyState === WebSocket.OPEN) {
            socket.send(JSON.stringify({
                type: "CHAT",
                msg: msg
            }));
        }
        chatInput.value = '';
    }
    // 💡 채팅창에 타이핑할 때 방향키를 눌러도 피카츄가 움직이지 않게 이벤트 버블링 차단!
    e.stopPropagation();
});