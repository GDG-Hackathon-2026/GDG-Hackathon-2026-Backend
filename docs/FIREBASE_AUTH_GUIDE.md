# Firebase Auth + 백엔드 API 연동 가이드 (프론트엔드)

> **읽는 사람**: GDG-Hackathon-2026-Front 리포에서 작업하는 Claude Code 세션 또는 팀원.
> 백엔드가 Firebase Admin SDK 기반 인증을 완료했고, 프론트는 Firebase Web SDK로 ID token 을 받아 백엔드에 붙이기만 하면 된다.

---

## 1. 전체 그림

```
[Next.js 브라우저]
  Firebase Web SDK
  signInAnonymously() → onAuthStateChanged → user.getIdToken()
                                                      │
                                                      ▼
  모든 API 호출에 Authorization: Bearer <idToken> 헤더 첨부
                                                      │
                                                      ▼
[Spring Boot 백엔드 on EC2]
  FirebaseTokenFilter 가 verifyIdToken → uid 추출
  @AuthenticationPrincipal String uid 로 컨트롤러에 전달
  탄소 누적·녹아내림 단계 계산 후 응답
```

ID token 은 JWT 이고 SDK 가 1 시간마다 자동 갱신한다. 프론트는 토큰 저장·refresh 걱정 없음.

---

## 2. 선행 조건 (거의 끝나있음)

백엔드 세션에서 이미 처리됨:

- [x] Firebase 프로젝트 = GCP 프로젝트 `gdg-hackathon-494307` 에 연결
- [x] Authentication 기능 활성화 + **익명** 로그인 방법 ON
- [x] Firebase Admin SDK SA key EC2 에 배포, 백엔드 토큰 검증 필터 가동
- [x] 백엔드 CORS 가 `http://13.125.197.228:3001`, `http://localhost:3000`, `http://localhost:3001` 허용
- [x] `/api/ping` 만 public, 나머지 `/api/**` 는 인증 필수

**프론트 세션이 추가로 해야 할 Firebase 콘솔 작업** (3 분):

### STEP 1 — Web 앱 등록 (firebaseConfig 값 얻기)
1. https://console.firebase.google.com → `gdg-hackathon-494307` 프로젝트 선택
2. ⚙ (설정 아이콘) → **프로젝트 설정** → **일반** 탭
3. 아래로 스크롤 → **"내 앱"** 섹션 → **`</>` (웹 아이콘)** 클릭
4. 앱 닉네임: `gdg-2026-web` (자유)
5. Firebase Hosting 체크박스는 **해제**
6. **앱 등록** 클릭
7. 화면에 표시되는 `firebaseConfig` 객체를 복사:
   ```js
   const firebaseConfig = {
     apiKey: "AIzaSy...",
     authDomain: "gdg-hackathon-494307.firebaseapp.com",
     projectId: "gdg-hackathon-494307",
     storageBucket: "gdg-hackathon-494307.appspot.com",
     messagingSenderId: "...",
     appId: "1:...:web:..."
   };
   ```
   이 값들은 **모두 public** — 유출돼도 안전 (Firebase 가 authorized domains, 역할, 토큰 검증으로 실제 인증 보안 유지)

### STEP 2 — Authorized Domains 추가
운영 환경 origin 을 Firebase 가 받아들이도록 등록.

1. Firebase 콘솔 → **Authentication** → **Settings** 탭 → **Authorized domains**
2. **도메인 추가** → `13.125.197.228` 입력 → 저장
3. `localhost` 는 기본 포함됨

> **안 하면**: 브라우저에서 `signInAnonymously()` 호출 시 `auth/unauthorized-domain` 에러.

---

## 3. 프론트 리포에 들어갈 코드

### 3-A. 패키지 설치
```bash
npm install firebase
```

### 3-B. `lib/firebase.ts` — 클라이언트 초기화
```ts
'use client';

import { initializeApp, getApps, type FirebaseApp } from 'firebase/app';
import { getAuth, type Auth } from 'firebase/auth';

const firebaseConfig = {
  apiKey: process.env.NEXT_PUBLIC_FIREBASE_API_KEY!,
  authDomain: process.env.NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN!,
  projectId: process.env.NEXT_PUBLIC_FIREBASE_PROJECT_ID!,
  appId: process.env.NEXT_PUBLIC_FIREBASE_APP_ID!,
  // storageBucket, messagingSenderId 는 현재 기능에 불필요
};

const app: FirebaseApp = getApps().length ? getApps()[0]! : initializeApp(firebaseConfig);
export const auth: Auth = getAuth(app);
```

### 3-C. `lib/auth-context.tsx` — 익명 로그인 자동 + React Context
```tsx
'use client';

import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { onAuthStateChanged, signInAnonymously, type User } from 'firebase/auth';
import { auth } from './firebase';

type AuthState = { user: User | null; ready: boolean };

const AuthContext = createContext<AuthState>({ user: null, ready: false });

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    const unsub = onAuthStateChanged(auth, (u) => {
      if (u) {
        setUser(u);
        setReady(true);
      } else {
        // 세션이 없으면 즉시 익명으로 로그인
        signInAnonymously(auth).catch((err) => {
          console.error('anonymous sign-in failed:', err);
          setReady(true); // 실패해도 UI 는 뜨게
        });
      }
    });
    return () => unsub();
  }, []);

  return <AuthContext.Provider value={{ user, ready }}>{children}</AuthContext.Provider>;
}

export const useAuth = () => useContext(AuthContext);
```

### 3-D. `app/layout.tsx` — Provider 주입
```tsx
import { AuthProvider } from '@/lib/auth-context';

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ko">
      <body>
        <AuthProvider>{children}</AuthProvider>
      </body>
    </html>
  );
}
```

### 3-E. `lib/api.ts` — 인증 토큰 자동 첨부 fetch 래퍼
```ts
import { auth } from './firebase';

const API_URL = process.env.NEXT_PUBLIC_API_URL!; // http://13.125.197.228:8080

async function fetchWithAuth(path: string, init?: RequestInit) {
  const u = auth.currentUser;
  if (!u) throw new Error('not authenticated yet — wait for AuthProvider.ready');
  const token = await u.getIdToken(); // 만료되면 SDK 가 자동 갱신
  const res = await fetch(`${API_URL}${path}`, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...init?.headers,
      Authorization: `Bearer ${token}`,
    },
  });
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
  return res.json();
}

export const api = {
  me: (): Promise<MeResponse> => fetchWithAuth('/api/me'),

  createConversation: (title?: string): Promise<Conversation> =>
    fetchWithAuth('/api/conversations', {
      method: 'POST',
      body: JSON.stringify({ title }),
    }),

  listConversations: (): Promise<Conversation[]> => fetchWithAuth('/api/conversations'),

  getConversation: (id: number): Promise<ConversationView> =>
    fetchWithAuth(`/api/conversations/${id}`),

  sendMessage: (id: number, content: string): Promise<SendResult> =>
    fetchWithAuth(`/api/conversations/${id}/messages`, {
      method: 'POST',
      body: JSON.stringify({ content }),
    }),
};

// ---- 응답 타입 (백엔드 record 에 대응) ----

export interface MeResponse {
  uid: string;
  carbonUsedG: number;
  stage: number;           // 0~5
  maxInputTokens: number;  // 현재 허용 prompt 길이 상한
  meltingPercent: number;  // 현재 stage 내 진행률 0~100
}

export interface Conversation {
  id: number;
  userUid: string;
  title: string;
  createdAt: string; // ISO-8601
}

export interface Message {
  id: number;
  conversationId: number;
  role: 'USER' | 'ASSISTANT';
  content: string;
  promptTokens: number | null;
  completionTokens: number | null;
  carbonG: number | null;
  createdAt: string;
}

export interface ConversationView {
  conversation: Conversation;
  messages: Message[];
}

export interface SendResult {
  assistantMessage: {
    id: number;
    content: string;
    promptTokens: number;
    completionTokens: number;
    carbonG: number;
  };
  carbonState: {
    totalCarbonG: number;
    stage: number;
    maxInputTokens: number;
    meltingPercent: number;
  };
  truncated: boolean;
}
```

### 3-F. 사용 예 — 페이지에서 호출
```tsx
'use client';

import { useEffect, useState } from 'react';
import { useAuth } from '@/lib/auth-context';
import { api, type MeResponse } from '@/lib/api';

export default function Home() {
  const { ready } = useAuth();
  const [me, setMe] = useState<MeResponse | null>(null);

  useEffect(() => {
    if (!ready) return;
    api.me().then(setMe).catch(console.error);
  }, [ready]);

  if (!ready) return <p>인증 중…</p>;
  if (!me) return <p>로딩…</p>;
  return (
    <pre>
      UID: {me.uid}
      {'\n'}누적 탄소: {me.carbonUsedG.toFixed(3)} gCO₂eq
      {'\n'}녹아내림 단계: {me.stage}/5 ({me.meltingPercent}%)
      {'\n'}허용 입력 토큰: {me.maxInputTokens}
    </pre>
  );
}
```

---

## 4. 환경변수 & 빌드 배선

### 로컬 개발 — `.env.local`
```
NEXT_PUBLIC_API_URL=http://localhost:8080
NEXT_PUBLIC_FIREBASE_API_KEY=AIza...
NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN=gdg-hackathon-494307.firebaseapp.com
NEXT_PUBLIC_FIREBASE_PROJECT_ID=gdg-hackathon-494307
NEXT_PUBLIC_FIREBASE_APP_ID=1:...:web:...
```

> 로컬에서 백엔드도 띄우고 테스트할 거면 `NEXT_PUBLIC_API_URL=http://localhost:8080` 쓰고 백엔드 쪽 `application.yml` 의 `spring.profiles.active=local` 로 기동.
> 운영 배포된 백엔드를 그대로 쓸 거면 `NEXT_PUBLIC_API_URL=http://13.125.197.228:8080`.

### `.env.example` 에도 추가
```
NEXT_PUBLIC_API_URL=http://13.125.197.228:8080
NEXT_PUBLIC_FIREBASE_API_KEY=
NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN=gdg-hackathon-494307.firebaseapp.com
NEXT_PUBLIC_FIREBASE_PROJECT_ID=gdg-hackathon-494307
NEXT_PUBLIC_FIREBASE_APP_ID=
```

### GitHub Secrets — 프론트 리포
`NEXT_PUBLIC_*` 는 **빌드 타임에 번들에 인라인**되므로 Docker 이미지 빌드 시 주입해야 한다.

| Secret | 값 |
|--------|-----|
| `FIREBASE_WEB_API_KEY` | Firebase 콘솔에서 복사한 apiKey |
| `FIREBASE_WEB_APP_ID` | Firebase 콘솔에서 복사한 appId |

`authDomain`, `projectId` 는 고정값이라 workflow 에 하드코딩해도 됨.

### `Dockerfile` — `ARG` 추가
기존 builder 스테이지에:
```dockerfile
ARG NEXT_PUBLIC_API_URL
ARG NEXT_PUBLIC_FIREBASE_API_KEY
ARG NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN
ARG NEXT_PUBLIC_FIREBASE_PROJECT_ID
ARG NEXT_PUBLIC_FIREBASE_APP_ID
ENV NEXT_PUBLIC_API_URL=$NEXT_PUBLIC_API_URL \
    NEXT_PUBLIC_FIREBASE_API_KEY=$NEXT_PUBLIC_FIREBASE_API_KEY \
    NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN=$NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN \
    NEXT_PUBLIC_FIREBASE_PROJECT_ID=$NEXT_PUBLIC_FIREBASE_PROJECT_ID \
    NEXT_PUBLIC_FIREBASE_APP_ID=$NEXT_PUBLIC_FIREBASE_APP_ID
```

### `.github/workflows/deploy.yml` — `build-args` 주입
`docker/build-push-action@v5` 의 `build-args`:
```yaml
build-args: |
  NEXT_PUBLIC_API_URL=http://13.125.197.228:8080
  NEXT_PUBLIC_FIREBASE_API_KEY=${{ secrets.FIREBASE_WEB_API_KEY }}
  NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN=gdg-hackathon-494307.firebaseapp.com
  NEXT_PUBLIC_FIREBASE_PROJECT_ID=gdg-hackathon-494307
  NEXT_PUBLIC_FIREBASE_APP_ID=${{ secrets.FIREBASE_WEB_APP_ID }}
```

(기존 `NEXT_PUBLIC_API_URL` 만 있던 부분을 위처럼 확장)

---

## 5. "녹아내림" UI 매핑 제안

백엔드가 내려주는 `stage` 와 `meltingPercent` 만으로 UI 강도 조절 가능. 예시 매핑:

| stage | 상태 | 시각 강도 예시 |
|-------|-----|---------------|
| 0 | 정상 | 원본 CSS 그대로 |
| 1 | 초기 균열 | 전체 채도 -5%, 글자 자간 +1% |
| 2 | 서서히 일그러짐 | `filter: blur(0.5px)` 일부 블록, 간헐적 `transform: skew(0.3deg)` |
| 3 | 눈에 띄게 변형 | 배경 그라디언트 "녹은 얼음" 톤, 텍스트 drop shadow 떨림 |
| 4 | 심각 | 채팅 입력창 width -20%, 경고 배너 ("데이터센터가 무너지는 중…") |
| 5 | 사용 불가 | 채팅 disable, 안내 페이지 — "당신의 UI/UX는 녹아버렸습니다" |

`meltingPercent` 는 각 stage 내부의 0~100% 진행률이므로, stage 경계에서 급변하지 않고 smooth 하게 보간할 때 사용:

```tsx
const blur = stage * 0.5 + (meltingPercent / 100) * 0.5; // px
style={{ filter: `blur(${blur}px)` }}
```

구체 디자인은 프론트 재량. 여기 스펙은 "stage + meltingPercent 두 값만 쓰면 모든 강도 조절 가능" 이라는 것만 공유.

---

## 6. 수동 테스트 (토큰 복사해서 curl)

브라우저 DevTools 콘솔 (프론트 앱 열린 상태) 에서:
```js
firebase.auth().currentUser.getIdToken().then(console.log);
// 또는 import 방식이면:
import { auth } from '@/lib/firebase';
auth.currentUser.getIdToken().then(console.log);
```

출력 토큰을 복사해서:
```bash
TOKEN="eyJhbGc..."

# 내 상태
curl http://13.125.197.228:8080/api/me -H "Authorization: Bearer $TOKEN"

# 대화 생성
curl -X POST http://13.125.197.228:8080/api/conversations \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"title":"안녕"}'

# 메시지 보내기 (id 는 위 응답의 id 사용)
curl -X POST http://13.125.197.228:8080/api/conversations/1/messages \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"content":"지구온난화에 대해 100자로 설명해줘"}'
```

이걸 반복하면 `/api/me` 응답의 `carbonUsedG` 가 누적되고 20g/50g/100g… 넘을 때마다 `stage` 가 올라가며 `maxInputTokens` 가 줄어드는 걸 확인할 수 있다.

---

## 7. 자주 밟는 함정

| 함정 | 증상 | 해결 |
|------|------|------|
| `auth.currentUser` 가 `null` | API 호출이 "not authenticated" | `AuthProvider.ready === true` 가 된 후에만 API 호출 |
| 익명 로그인 실패 `auth/unauthorized-domain` | Firebase 가 origin 거부 | Authorized Domains 에 `13.125.197.228` 추가 |
| API 에서 403 | 토큰 없거나 잘못됨 | DevTools Network 탭에서 Authorization 헤더 실제로 붙었는지 확인. Bearer 대소문자 주의 |
| CORS 에러 | 백엔드가 origin 거부 | `http://13.125.197.228:3001` 또는 `http://localhost:3000/3001` 인지 확인. 다른 포트는 백엔드 `CorsConfig.java` 에 추가 필요 (백엔드 리포 PR) |
| `NEXT_PUBLIC_FIREBASE_API_KEY` undefined | 브라우저 콘솔에서 `Firebase: Error (auth/invalid-api-key)` | 빌드 타임 주입 — Dockerfile `ARG` + workflow `build-args` 둘 다 필요 |
| 토큰 1 시간 지나니 401 뜨기 시작 | ID token 만료 | `getIdToken()` 을 매 호출마다 await (매번 fresh token 반환. 만료 임박 시 자동 refresh) |
| 새 기기에서 다른 UID | 익명 UID 는 기기별 | 정상 동작. 동일 사용자 유지 원하면 Google 로그인으로 업그레이드 (8 섹션) |
| 익명 계정 삭제 정책 | Firebase 기본 30일 후 익명 계정 자동 삭제 | Authentication → Settings → "Anonymous user cleanup" 설정. 해커톤 데모 기간엔 무관 |

---

## 8. 익명 → Google 로그인 업그레이드 (선택, 나중에)

익명 UID 를 Google 계정에 "연결" 하면 탄소 누적 히스토리를 그대로 유지한 채 실명 계정으로 전환 가능. 기기 간 동기화·데이터 백업 용도.

```ts
import { GoogleAuthProvider, linkWithPopup } from 'firebase/auth';

const googleProvider = new GoogleAuthProvider();
const linkedUser = (await linkWithPopup(auth.currentUser!, googleProvider)).user;
// 동일 UID 유지, email 필드만 추가됨
```

Firebase 콘솔 → Authentication → Sign-in method → **Google** 을 "사용 설정" 해야 한다 (백엔드 세션 가이드에 선택 스텝으로 언급했음).

---

## 9. 체크리스트 (프론트 세션용)

- [ ] Firebase 콘솔에서 Web 앱 등록 + firebaseConfig 값 확보
- [ ] Authorized Domains 에 `13.125.197.228` 추가
- [ ] `npm install firebase`
- [ ] `lib/firebase.ts`, `lib/auth-context.tsx`, `lib/api.ts` 추가
- [ ] `app/layout.tsx` 에 `AuthProvider` 래핑
- [ ] `.env.local` 에 `NEXT_PUBLIC_FIREBASE_*` 설정
- [ ] Dockerfile 에 Firebase `ARG`/`ENV` 추가
- [ ] GitHub Secrets 에 `FIREBASE_WEB_API_KEY`, `FIREBASE_WEB_APP_ID` 등록
- [ ] workflow `build-args` 확장
- [ ] 테스트: 페이지 접속 → DevTools 에서 `/api/me` 200 응답 확인

---

## 10. 참고 링크

- Firebase Auth 웹 가이드: https://firebase.google.com/docs/auth/web/start
- 익명 로그인: https://firebase.google.com/docs/auth/web/anonymous-auth
- Next.js 환경변수: https://nextjs.org/docs/app/building-your-application/configuring/environment-variables
- 백엔드 리포 관련 파일 (참고용):
  - [src/main/java/com/moggo/_gdg/config/SecurityConfig.java](../src/main/java/com/moggo/_gdg/config/SecurityConfig.java)
  - [src/main/java/com/moggo/_gdg/security/FirebaseTokenFilter.java](../src/main/java/com/moggo/_gdg/security/FirebaseTokenFilter.java)
  - [src/main/java/com/moggo/_gdg/service/ChatService.java](../src/main/java/com/moggo/_gdg/service/ChatService.java)
  - [src/main/java/com/moggo/_gdg/service/CarbonPolicy.java](../src/main/java/com/moggo/_gdg/service/CarbonPolicy.java)
