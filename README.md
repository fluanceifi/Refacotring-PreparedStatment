# PreparedStatement 공유 캐시 구현

> MySQL Connector/J의 한계를 넘어, 커넥션 간 PreparedStatement 캐시를 공유하는 구현체

---

## 1. 배경지식

### JDBC / DBMS 계층 구조

```
애플리케이션
     ↓
JDBC 인터페이스 (Connection, PreparedStatement ...)
     ↓
JDBC Driver 구현체 (MySQL Connector/J)
     ↓
MySQL 서버
```

- **JDBC**는 JVM과 DBMS 사이를 표준화한 인터페이스
- **PreparedStatement** 기능 자체는 MySQL 서버가 제공
- JDBC는 그 기능을 Java에서 쓸 수 있도록 연결하는 다리 역할

---

## 2. PreparedStatement란?

MySQL에서 쿼리를 **미리 파싱/컴파일해서 저장**해두는 기능

```sql
PREPARE stmt FROM 'SELECT * FROM users WHERE id = ?';
SET @id = 'bumjin';
EXECUTE stmt USING @id;
DEALLOCATE PREPARE stmt;
```

### PREPARE 시 내부 동작

| 단계 | 설명 |
|------|------|
| 파싱 | 쿼리 문법 검사 |
| 실행 계획 수립 | 인덱스 선택, 조인 순서, 비용 계산 |
| 저장 | `?` 자리에 값만 받으면 되는 형태로 메모리에 저장 |

**장점**: 반복 실행 시 파싱/컴파일 생략 → 성능 향상 + SQL Injection 방어

---

## 3. Connection Pool에서 캐싱하면 안 되는 이유 (안티패턴)

### HikariCP의 관점

PreparedStatement 캐시는 **커넥션 단위**로만 가능

```
쿼리 250개 × 커넥션 20개 = 5,000개 (중복 저장)
```

반면 JDBC Driver 레이어에서 캐싱하면:

```
쿼리 250개 (커넥션 간 공유 가능)
```

→ HikariCP는 이 이유로 PreparedStatement 캐싱을 **의도적으로 지원하지 않음**  
→ 대신 MySQL Connector/J에 캐싱을 위임

```java
config.addDataSourceProperty("cachePrepStmts", "true");
config.addDataSourceProperty("prepStmtCacheSize", "250");
```

---

## 4. MySQL Connector/J의 주요 설정

| 속성 | 설명 |
|------|------|
| `useServerPrepStmts` | true: MySQL 서버에 실제 PREPARE 전송 / false: 드라이버에서 처리 |
| `cachePrepStmts` | PreparedStatement 캐시 활성화 여부 |
| `prepStmtCacheSize` | 커넥션당 캐시할 수 있는 최대 PreparedStatement 수 |
| `prepStmtCacheSqlLimit` | 캐시할 수 있는 최대 쿼리 길이 |

### useServerPrepStmts에 따른 구현체 차이

```
useServerPrepStmts=false → ClientPreparedStatement (드라이버에서 처리)
useServerPrepStmts=true  → ServerPreparedStatement (MySQL 서버에서 처리)
```

### 캐시 LRU 제거 시

`prepStmtCacheSize` 초과 시 가장 오래된 항목 제거  
→ `useServerPrepStmts=true`였다면 MySQL에 `DEALLOCATE` 전송

---

## 5. 성능 테스트 결과 (쿼리 20,000회 반복)

| 설정 | MySQL PREPARE | 캐시 | 실행시간 |
|------|:---:|:---:|------|
| useServerPrepStmts=true, cachePrepStmts=true | ✅ | ✅ | 약 4분 |
| useServerPrepStmts=true, cachePrepStmts=false | ✅ | ❌ | **약 21분** |
| useServerPrepStmts=false, cachePrepStmts=true | ❌ | ✅ | 약 4분 |
| useServerPrepStmts=false, cachePrepStmts=false | ❌ | ❌ | 약 4분 |

**2번이 압도적으로 느린 이유**  
매 쿼리마다 `PREPARE → 실행 → DEALLOCATE` 를 20,000번 반복 → 네트워크 왕복 비용 3배

---

## 6. MySQL Connector/J의 한계

HikariCP는 기대했지만 실제 구현은 다름

```
HikariCP의 기대:      커넥션 간 캐시 공유 가능
MySQL Connector/J:    여전히 커넥션당 독립 캐시
```

→ 이론적 이상과 실제 구현 사이의 괴리

---

## 7. 공유 캐시 구현체

### 핵심 아이디어

- MySQL Connector/J가 하지 않은 **커넥션 간 캐시 공유**를 직접 구현
- PreparedStatement 객체는 커넥션마다 존재하지만, **캐시 히트 판단 레이어를 공유**

### 구조

```
sharedCache (static → 모든 커넥션이 공유)
  └── "SELECT * FROM users WHERE id = ?" → CachedEntry
        ├── refCount (AtomicInteger): 현재 사용 중인 커넥션 수
        └── stmtPerConnection (ConcurrentHashMap)
              ├── conn1 → PreparedStatement
              ├── conn2 → PreparedStatement
              └── conn3 → PreparedStatement
```

### 동시성 제어

| 수단 | 역할 |
|------|------|
| `ConcurrentHashMap` | 여러 커넥션이 동시에 캐시 접근 시 안전 보장 |
| `AtomicInteger (refCount)` | 현재 사용 중인 커넥션 수를 thread-safe하게 관리 |
| `computeIfAbsent` | 캐시 엔트리 생성 시 중복 생성 방지 |

### prepareStatement() 흐름

```
1. 공유 캐시에 해당 쿼리가 있는지 확인
   ├── 없으면 → MySQL에 PREPARE 전송 후 캐시 등록
   └── 있으면 (캐시 히트)
         ├── 이 커넥션용 stmt가 있으면 → 재사용
         └── 없으면 → 이 커넥션용 stmt만 새로 생성 (PREPARE 재전송 없음)
```

### LRU 제거 조건

- 캐시가 `MAX_CACHE_SIZE(250)` 초과 시 가장 오래된 항목 제거
- 단, `refCount > 0` (현재 사용 중)인 항목은 제거 대상 제외
- 제거 시 각 커넥션의 stmt.close() → MySQL에 `DEALLOCATE` 전송

### 기존 Connector/J vs 이 구현체 비교

```
기존 Connector/J:
  conn1 → [캐시 250개]
  conn2 → [캐시 250개]   → 총 250 * 커넥션 수
  conn3 → [캐시 250개]

이 구현체:
  conn1 ↘
  conn2 → [공유 캐시 250개]   → 총 250개 (커넥션 수 무관)
  conn3 ↗
```

---

## 8. 유의사항

### MySQL 서버 설정
```sql
-- MySQL 서버의 prepared statement 최대 개수 확인
SHOW VARIABLES LIKE 'max_prepared_stmt_count';
-- 기본값: 16,382개
-- 초과 시 캐시에서 제거되는 게 아니라 1461 에러 발생
```
애플리케이션 레벨과 MySQL 서버 레벨은 별개로 존재합니다.<br>
따라서 애플리케이션 단에서 LRU로 열심히 캐시를 지워도 MySQL 서버 단에서는 초과 시 LRU 제거 없고, 그냥 1461 에러 발생합니다.

### 이 구현체 사용 시
- `cachePrepStmts=false` 필수 (드라이버 캐시와 충돌 방지)
- `useServerPrepStmts=true` 권장 (MySQL 서버 레벨 캐시 활용)

---

## 9. 결론

| | Connection Pool 캐시 | Driver 레이어 캐시 | 이 구현체 |
|--|:---:|:---:|:---:|
| 캐시 공유 범위 | 커넥션당 독립 | 커넥션당 독립 (Connector/J 기준) | **커넥션 간 공유** |
| 동시성 제어 | - | - | ConcurrentHashMap + AtomicInteger |
| LRU 제거 | ✅ | ✅ | ✅ |
| DEALLOCATE 타이밍 | stmt.close() 즉시 | stmt.close() 즉시 | **refCount = 0 일 때** |

> 저스펙 환경(메모리 제약)에서 커넥션 수가 많을수록 이 구현체의 메모리 절약 효과가 커집니다.

---
