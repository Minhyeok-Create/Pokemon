#  WebSocket 기반 실시간 멀티플레이어 2D 포켓몬 온라인

> **포켓몬스터 골드 버전을 오마주한 웹 브라우저 기반의 실시간 멀티플레이어 RPG 게임입니다.**
> 백엔드 스프링 부트와 프론트엔드 바닐라 자바스크립트 HTML5 Canvas를 설계하고 융합하여, 서버 지연 시간 최적화 및 끊김 없는 인게임 분기 엔진을 구현했습니다.

<br>

## 배포 서버 URL : https://pokemon-six-smoky.vercel.app/

## Tech Stacks (기술 스택)
* **Backend:** Java 17, Spring Boot, Spring Webflux (WebSocket Subsystem), Spring Data JPA
* **Frontend:** Vanilla JavaScript, HTML5 Canvas API, CSS3
* **Database & ORM:** MySQL (or H2/PostgreSQL), Hibernate
* **Deployment:** Cloud Environments (Render, Git Webhook CI/CD)

---

## 핵심 기술 및 문제 해결 경험

### 1. 지능형 웹소켓 재연결 및 패킷 예약 시스템 설계
* **Challenge:** 무료 배포 클라우드 인프라 특성상 최초 접속 시 서버가 깨어나는 데 고질적인 인프라 지연(Cold Start, 3~5분)이 발생했습니다. 이 기간 동안 유저가 가입/로그인을 시도하면 웹소켓 연결이 거부되어 데이터가 유실되고 UX가 심각하게 저해되는 문제가 있었습니다.
* **Solution:** 클라이언트 `NetworkManager`에 `onclose` 센서를 활용한 **1.5초 주기 자동 재연결(Auto Reconnect) 로직**을 탑재하고, 소켓이 닫힌 상태에서 유저가 발생시킨 인증 패킷을 메모리 버퍼(`pendingRequest`)에 **예약 보관(Queueing)** 하도록 아키텍처를 개조했습니다.
* **Result:** 유저의 중복 조작을 원천 차단하고 핸드셰이크가 완료되는 타이밍을 인터셉트하여 예약 패킷을 자동 사출함으로써, 서버 가동과 동시에 **100% 자동으로 게임 세션 진입을 완료하는 UX**를 구축했습니다.

<img width="969" height="776" alt="서버가동대기" src="https://github.com/user-attachments/assets/dcdd9084-ddad-43b6-bfbb-67745c4755a4" />

---

### 2. 레이턴시 극복을 위한 선형 보간법 렌더링 엔진
* **Challenge:** 배포 서버 환경에서 필연적으로 발생하는 네트워크 지연(Latency, 100~200ms)과 패킷 도달 간격의 불일치(Jitter)로 인해, 타 플레이어의 동기화 캐릭터가 화면에서 순간이동 하듯 뚝뚝 끊기며 흐려지는 프레임 드랍 현상이 발생했습니다.
* **Solution:** 서버로부터 수신한 고정 좌표를 캐릭터에 즉시 대입하지 않고, 매 프레임마다 현재 위치에서 새로운 목적지 좌표(`targetX`, `targetY`)까지의 격차를 일정 배율(20%)로 감쇠하며 부드럽게 추적하는 **선형 보간(Lerp) 로직**을 메인 렌더 프레임 루프에 이식했습니다.
* **Result:** 네트워크 도달 시점이 불규칙한 환경에서도 모든 멀티플레이어 캐릭터의 무빙을 60fps 수준으로 연속 렌더링하는 최적화를 달성했습니다.

<img width="659" height="776" alt="이동" src="https://github.com/user-attachments/assets/5990b1f9-0836-41d7-807a-8072e3247865" />

---

### 3. 실시간 전방위 브로드캐스팅 및 월드 동기화 
* **Challenge:** 다중 접속 환경에서 유저의 행동(이동, 실시간 채팅, 포탈 맵 대륙 이동) 데이터가 유실 없이 전원에게 즉시 공유되어야 하는 고속 동기화가 요구되었습니다.
* **Solution:** 백엔드 영역에서 `ConcurrentHashMap` 고속 메모리 보관소를 가동하여 실시간 가용 세션을 추적하고, 동시성 스레드 안전 가드를 통해 이벤트 발생 시 즉시 월드 전체 유저에게 지연 없이 데이터를 사출하는 전방위 브로드캐스팅 파이프라인을 구축했습니다.

| 실시간 인게임 채팅 시스템 | 대륙 포탈 이동 및 맵 스위칭 공정 |
| :---: | :---: |
| <img width="1179" height="728" alt="채팅" src="https://github.com/user-attachments/assets/911cdf14-643b-4a03-82d5-e06c13dfb118" /> | <img width="1190" height="768" alt="맵이동" src="https://github.com/user-attachments/assets/1cae03e7-d59a-4570-ae05-97de2da92c72" /> |

---

### 4. 정통 포켓몬 콘텐츠 역학 관계 데이터 모델링 및 하이브리드 배틀 필드
* **기방 한도 및 영구 방생 시스템:** 6마리 소지 제한 하드 가드를 데이터 레이어에 반영하고, 인벤토리 포화 시 JPA 영속성 컨텍스트 타격 삭제(`deleteById`) 연동을 통한 대자연 방생 시스템으로 데이터 효율성과 인게임 밸런스를 모두 확보했습니다.
* **포켓몬센터 청정 힐링 공정:** 특정 좌표 타일 충돌 검사(Collision Check) 엔진을 연동하여, 입장 시 스레드 풀 안전 가방 리로드를 통해 소지 몬스터의 전체 체력을 실시간으로 영구 전원 완치 처리합니다.
* **레벨 스케일링 기반 속성 공방전:** 각 포켓몬의 개별 성장 레벨 비례형 데미지 수식 `(레벨 * 2) + 난수` 공식에 정통 속성 상성 관계 배율(물/불/풀 상호 역학 1.5배 및 0.5배 가중치)을 실시간 공격 가우징에 바인딩했습니다.

| 실시간 가방 데이터 영구 방생 | 포켓몬센터 충돌 검사 및 회복 | 야생전 조우 및 선발 확정 포획 |
| :---: | :---: | :---: |
| <img width="654" height="848" alt="방생" src="https://github.com/user-attachments/assets/6c1d7d5a-c069-4c66-80b8-4a0fee926602" /> | <img width="654" height="848" alt="회복" src="https://github.com/user-attachments/assets/bb537c55-0742-43fe-98d0-3fc2ba265e52" /> | <img width="654" height="848" alt="전투 및 포획" src="https://github.com/user-attachments/assets/420b98fe-43d7-481e-8a01-ac6a2270b7b1" /> |

---

### 5. 멀티플레이어 대인전(PvP) 매칭 결투 시스템 및 모바일 크로스 플랫폼
* **Challenge:** 야생전과 달리 두 명의 실제 플레이어 간 동시성 상태 제어와 선발 투수 지목 페이즈, 항복/기권 및 강제 이탈 시 예외 정산 처리가 수반되는 복잡한 실시간 턴제 공방전 구현이 필요했습니다.
* **Solution:** 고유 룸 계약 매커니즘(`roomId`)을 개설하여 방 점유율을 추적하고, 프론트엔드 단에서 마우스 조준 사격형 결투 신청 티켓 플로팅 UI 및 선택 비활성화(출격 대기 락) 시각 피드백을 주입했습니다. 또한, 사망 시 태초 마을 강제 이송 패킷 및 브라우저 강제 종료 시 남은 유저의 부전승 자동 스케줄러 세션을 빌드했습니다.
* **Cross-Platform:** 모바일 브라우저 터치 패널 제어를 위한 가상 조이스틱 터치 이벤트 핸들러 연속 인터벌 모듈을 장착하여 기기 제한 없는 플레이가 가능합니다.

|  1:1 트레이너 진검승부 PvP 아레나 | 📱 모바일 가상 조이스틱 및 크로스 플랫폼 |
| :---: | :---: |
| <img width="1191" height="768" alt="pvp" src="https://github.com/user-attachments/assets/cbced514-0abf-4252-8ffa-2ee5b9dbb233" /> | <img width="530" height="722" alt="모바일" src="https://github.com/user-attachments/assets/5ef53385-03c9-4cac-bb86-91fc46f920e6" /> |

<br>

|  전멸 및 태초마을 소환 시퀀스 |
| :---: |
| <img width="496" height="691" alt="사망" src="https://github.com/user-attachments/assets/748ad5d1-4f46-4f3e-92b0-09ca579ccdce" /> |
