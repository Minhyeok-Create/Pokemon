// ==========================================
// [MODULE 2] GAME RENDERER CLASS (입체 그래픽 코어)
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
        this.ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);
        this.ctx.imageSmoothingEnabled = false;

        let bgStyle = '#7bc673'; let gridColor = '#63b55a'; let wallColor = '#316331'; let wallTopColor = '#5a9c5a'; let bushColor = '#429c42'; let bushLeafColor = '#a5e7a5';
        
        if (this.game.currentMapName === 'field') {
            bgStyle = '#dcd095'; gridColor = '#dbae5c'; wallColor = '#8c763e'; wallTopColor = '#cca752'; bushColor = '#b5933a'; bushLeafColor = '#f0d99e';
        } else if (this.game.currentMapName === 'dungeon') {
            bgStyle = '#2b2b2b'; gridColor = '#1f1f1f'; wallColor = '#151515'; wallTopColor = '#444444'; bushColor = '#962d22'; bushLeafColor = '#e74c3c';
        }

        for (let r = 0; r < 5; r++) {
            for (let c = 0; c < 5; c++) {
                let tx = c * TILE_SIZE; let ty = r * TILE_SIZE;
                
                if (currentMap[r][c] === 0) {
                    this.ctx.fillStyle = bgStyle; this.ctx.fillRect(tx, ty, TILE_SIZE, TILE_SIZE);
                    this.ctx.fillStyle = gridColor;
                    for (let dy = 15; dy < TILE_SIZE; dy += 30) {
                        this.ctx.fillRect(tx + 20, ty + dy, 8, 2);
                        this.ctx.fillRect(tx + 55, ty + dy + 12, 8, 2);
                    }
                    this.ctx.strokeStyle = gridColor; this.ctx.lineWidth = 1; this.ctx.strokeRect(tx, ty, TILE_SIZE, TILE_SIZE);
                }
                else if (currentMap[r][c] === 1) {
                    this.ctx.fillStyle = wallColor; this.ctx.fillRect(tx, ty, TILE_SIZE, TILE_SIZE);
                    this.ctx.fillStyle = wallTopColor; this.ctx.fillRect(tx + 4, ty + 4, TILE_SIZE - 8, TILE_SIZE - 8);
                    this.ctx.fillStyle = wallColor; this.ctx.fillRect(tx + 12, ty + 12, TILE_SIZE - 24, TILE_SIZE - 24);
                    this.ctx.strokeStyle = 'rgba(0,0,0,0.4)'; this.ctx.lineWidth = 2; this.ctx.strokeRect(tx, ty, TILE_SIZE, TILE_SIZE);
                }
                else if (currentMap[r][c] === 2) {
                    this.ctx.fillStyle = bgStyle; this.ctx.fillRect(tx, ty, TILE_SIZE, TILE_SIZE);
                    this.ctx.strokeStyle = gridColor; this.ctx.strokeRect(tx, ty, TILE_SIZE, TILE_SIZE);
                    
                    const half = TILE_SIZE / 2;
                    for (let si = 0; si < 2; si++) {
                        for (let sj = 0; sj < 2; sj++) {
                            let stx = tx + (si * half); let sty = ty + (sj * half);
                            this.ctx.fillStyle = bushColor;
                            this.ctx.fillRect(stx + 8, sty + 10, 24, 8);
                            this.ctx.fillRect(stx + 4, sty + 16, 8, 14);
                            this.ctx.fillRect(stx + 28, sty + 16, 8, 14);
                            this.ctx.fillStyle = bushLeafColor;
                            this.ctx.fillRect(stx + 14, sty + 12, 12, 3);
                        }
                    }
                }
                else if (currentMap[r][c] === 3) {
                    this.ctx.fillStyle = '#294eb8'; this.ctx.fillRect(tx, ty, TILE_SIZE, TILE_SIZE); 
                    this.ctx.fillStyle = '#ffffff'; this.ctx.fillRect(tx + 8, ty + 24, TILE_SIZE - 16, TILE_SIZE - 24); 
                    this.ctx.fillStyle = '#e74c3c'; this.ctx.fillRect(tx + 34, ty + 42, 12, 26); this.ctx.fillRect(tx + 27, ty + 49, 26, 12);
                    this.ctx.strokeStyle = '#111'; this.ctx.lineWidth = 3; this.ctx.strokeRect(tx, ty, TILE_SIZE, TILE_SIZE);
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
            
            this.ctx.font = 'bold 12px sans-serif';
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
        this.ctx.fillStyle = '#111111'; this.ctx.textAlign = 'center'; this.ctx.font = 'bold 12px sans-serif'; this.ctx.fillText(text, bx + (bubbleWidth / 2), by + 16); this.ctx.textAlign = 'left';
    }
}