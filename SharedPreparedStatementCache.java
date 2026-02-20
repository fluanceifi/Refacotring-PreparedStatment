package dev.test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class SharedPreparedStatementCache {

	private static final int MAX_CACHE_SIZE = 250;
	private static final ConcurrentHashMap<String, CachedEntry> sharedCache = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<String, Long> lastAccessTime = new ConcurrentHashMap<>();

	static class CachedEntry {
		final String sql;
		final ConcurrentHashMap<String, PreparedStatement> stmtPerConnection = new ConcurrentHashMap<>();
		final AtomicInteger refCount = new AtomicInteger(0);
		CachedEntry(String sql) { this.sql = sql; }
	}

	static class ManagedPreparedStatement implements AutoCloseable {
		private final PreparedStatement delegate;
		private final CachedEntry entry;
		private boolean closed = false;

		ManagedPreparedStatement(PreparedStatement delegate, CachedEntry entry) {
			this.delegate = delegate;
			this.entry = entry;
		}

		public void setLong(int parameterIndex, long x) throws SQLException {
			delegate.setLong(parameterIndex, x);
		}

		public void setString(int parameterIndex, String x) throws SQLException { delegate.setString(parameterIndex, x); }

		public ResultSet executeQuery() throws SQLException {
			return delegate.executeQuery();
		}

		@Override
		public void close() {
			if (closed) return;
			closed = true;
			entry.refCount.decrementAndGet();
		}
	}

	static class SharedCacheConnection {
		private final Connection realConnection;
		private final String connectionId;

		SharedCacheConnection(Connection realConnection, String name) {
			this.realConnection = realConnection;
			this.connectionId = name;
		}

		public ManagedPreparedStatement prepareStatement(String sql) throws SQLException {
			lastAccessTime.put(sql, System.currentTimeMillis());
			boolean isNew = !sharedCache.containsKey(sql);

			CachedEntry entry = sharedCache.computeIfAbsent(sql, k -> {
				evictIfNecessary();
				return new CachedEntry(k);
			});

			PreparedStatement existing = entry.stmtPerConnection.get(connectionId);
			if (existing != null && !isClosed(existing)) {
				entry.refCount.incrementAndGet();
				System.out.println("  [" + connectionId + "] 커넥션 내 캐시 히트 → PreparedStatement 재사용");
				return new ManagedPreparedStatement(existing, entry);
			}

			if (isNew) {
				System.out.println("  [" + connectionId + "] 공유 캐시 미스 → MySQL에 PREPARE 전송");
			} else {
				System.out.println("  [" + connectionId + "] 공유 캐시 히트 → 이 커넥션용 stmt만 새로 생성 (PREPARE 재전송 없음)");
			}

			PreparedStatement newStmt = realConnection.prepareStatement(sql);
			entry.stmtPerConnection.put(connectionId, newStmt);
			entry.refCount.incrementAndGet();
			return new ManagedPreparedStatement(newStmt, entry);
		}

		private boolean isClosed(PreparedStatement stmt) {
			try { return stmt.isClosed(); } catch (SQLException e) { return true; }
		}

		private void evictIfNecessary() {
			if (sharedCache.size() < MAX_CACHE_SIZE) return;
			lastAccessTime.entrySet().stream()
				.filter(e -> {
					CachedEntry entry = sharedCache.get(e.getKey());
					return entry != null && entry.refCount.get() == 0;
				})
				.min((a, b) -> Long.compare(a.getValue(), b.getValue()))
				.ifPresent(oldest -> {
					CachedEntry removed = sharedCache.remove(oldest.getKey());
					lastAccessTime.remove(oldest.getKey());
					if (removed != null) {
						System.out.println("  [LRU 제거] 쿼리 캐시에서 제거: " + oldest.getKey());
						removed.stmtPerConnection.forEach((connId, stmt) -> {
							try { stmt.close(); } catch (SQLException ignored) {}
						});
					}
				});
		}

		public void close() throws SQLException {
			sharedCache.forEach((sql, entry) -> {
				PreparedStatement stmt = entry.stmtPerConnection.remove(connectionId);
				if (stmt != null) {
					try { stmt.close(); } catch (SQLException ignored) {}
				}
			});
			realConnection.close();
			System.out.println("  [" + connectionId + "] 커넥션 종료 → 해당 커넥션의 stmt 정리");
		}
	}

	// ResultSet을 컬럼명과 함께 출력하는 유틸
	private static void printResultSet(String connName, ResultSet rs) throws SQLException {
		ResultSetMetaData meta = rs.getMetaData();
		int colCount = meta.getColumnCount();

		// 컬럼 헤더 출력
		StringBuilder header = new StringBuilder("  │ ");
		for (int i = 1; i <= colCount; i++) {
			header.append(String.format("%-15s", meta.getColumnName(i))).append(" │ ");
		}
		String line = "  " + "─".repeat(header.length() - 2);
		System.out.println(line);
		System.out.println(header);
		System.out.println(line);

		// 행 출력
		boolean hasRow = false;
		while (rs.next()) {
			hasRow = true;
			StringBuilder row = new StringBuilder("  │ ");
			for (int i = 1; i <= colCount; i++) {
				row.append(String.format("%-15s", rs.getString(i))).append(" │ ");
			}
			System.out.println(row);
		}
		if (!hasRow) {
			System.out.println("  │ (결과 없음)");
		}
		System.out.println(line);
	}

	public static void main(String[] args) throws Exception {
		String jdbcUrl = "jdbc:mysql://localhost:3306/testdb?useServerPrepStmts=true&cachePrepStmts=false";
		String user = "root", password = "1234";

		SharedCacheConnection conn1 = new SharedCacheConnection(DriverManager.getConnection(jdbcUrl, user, password), "conn1");
		SharedCacheConnection conn2 = new SharedCacheConnection(DriverManager.getConnection(jdbcUrl, user, password), "conn2");
		SharedCacheConnection conn3 = new SharedCacheConnection(DriverManager.getConnection(jdbcUrl, user, password), "conn3");
		SharedCacheConnection conn4 = new SharedCacheConnection(DriverManager.getConnection(jdbcUrl, user, password), "conn4");

		String sql = "SELECT * FROM users WHERE id = ?";

		System.out.println("\n=== conn1 실행 ===");
		try (ManagedPreparedStatement ms1 = conn1.prepareStatement(sql)) {
			ms1.setString(1, "bumjin");
			ResultSet rs = ms1.executeQuery();
			printResultSet("conn1", rs);
			rs.close();
			System.out.println("  conn1 쿼리 완료 | 공유 캐시 크기: " + sharedCache.size());
		}

		System.out.println("\n=== conn2 실행 ===");
		try (ManagedPreparedStatement ms2 = conn2.prepareStatement(sql)) {
			ms2.setString(1, "erwins");
			ResultSet rs = ms2.executeQuery();
			printResultSet("conn2", rs);
			rs.close();
			System.out.println("  conn2 쿼리 완료 | 공유 캐시 크기: " + sharedCache.size());
		}

		System.out.println("\n=== conn3 실행 ===");
		try (ManagedPreparedStatement ms3 = conn3.prepareStatement(sql)) {
			ms3.setString(1, "green");
			ResultSet rs = ms3.executeQuery();
			printResultSet("conn3", rs);
			rs.close();
			System.out.println("  conn3 쿼리 완료 | 공유 캐시 크기: " + sharedCache.size());
		}

		System.out.println("\n=== conn4 실행 ===");
		try (ManagedPreparedStatement ms4 = conn4.prepareStatement(sql)) {
			ms4.setString(1, "joytouch");
			ResultSet rs = ms4.executeQuery();
			printResultSet("conn4", rs);
			rs.close();
			System.out.println("  conn4 쿼리 완료 | 공유 캐시 크기: " + sharedCache.size());
		}

		System.out.println("\n=== 커넥션 종료 ===");
		conn1.close();
		conn2.close();
		conn3.close();
		conn4.close();
	}
}


/*
=== conn1 실행 ===
  [conn1] 공유 캐시 미스 → MySQL에 PREPARE 전송
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  │ id              │ name            │ password        │ Level           │ Login           │ Recommend       │ email           │ 
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  │ bumjin          │ 박범진             │ p1              │ 1               │ 49              │ 0               │ gksrnr66@gmail.com │ 
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  conn1 쿼리 완료 | 공유 캐시 크기: 1

=== conn2 실행 ===
  [conn2] 공유 캐시 히트 → 이 커넥션용 stmt만 새로 생성 (PREPARE 재전송 없음)
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  │ id              │ name            │ password        │ Level           │ Login           │ Recommend       │ email           │ 
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  │ erwins          │ 신승한             │ p3              │ 2               │ 60              │ 29              │ gksrnr66@gmail.com │ 
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  conn2 쿼리 완료 | 공유 캐시 크기: 1

=== conn3 실행 ===
  [conn3] 공유 캐시 히트 → 이 커넥션용 stmt만 새로 생성 (PREPARE 재전송 없음)
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  │ id              │ name            │ password        │ Level           │ Login           │ Recommend       │ email           │ 
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  │ green           │ 오민규             │ p5              │ 3               │ 100             │ 2147483647      │ gksrnr66@gmail.com │ 
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  conn3 쿼리 완료 | 공유 캐시 크기: 1

=== conn4 실행 ===
  [conn4] 공유 캐시 히트 → 이 커넥션용 stmt만 새로 생성 (PREPARE 재전송 없음)
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  │ id              │ name            │ password        │ Level           │ Login           │ Recommend       │ email           │ 
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  │ joytouch        │ 강명성             │ p2              │ 1               │ 50              │ 0               │ gksrnr66@gmail.com │ 
  ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  conn4 쿼리 완료 | 공유 캐시 크기: 1

=== 커넥션 종료 ===
  [conn1] 커넥션 종료 → 해당 커넥션의 stmt 정리
  [conn2] 커넥션 종료 → 해당 커넥션의 stmt 정리
  [conn3] 커넥션 종료 → 해당 커넥션의 stmt 정리
  [conn4] 커넥션 종료 → 해당 커넥션의 stmt 정리

*/
