package org.yb.pgsql;

import static org.yb.AssertionWrappers.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.*;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.yb.util.ThreadUtil;
import org.yb.util.YBTestRunnerNonTsanOnly;

@RunWith(value = YBTestRunnerNonTsanOnly.class)
public class TestIndexBackfill extends BasePgSQLTest {
  private static final Logger LOG = LoggerFactory.getLogger(TestIndexBackfill.class);

  private static final int AWAIT_TIMEOUT_SEC = 60;

  @Override
  protected Map<String, String> getMasterAndTServerFlags() {
    Map<String, String> flagMap = super.getMasterAndTServerFlags();
    flagMap.put("ysql_disable_index_backfill", "false");
    return flagMap;
  }

  @Test
  public void noop() {
    // Avoid Jenkins erroring out with "No runnable methods" until we in-ignore the real test
  }

  // TODO(alex, jason): Enable when the YSQL backfill is fully implemented
  @Ignore
  public void concurrentAsc() throws Exception {
    int minThreads = 2;
    int insertsChunkSize = 100;
    String tableName = "concurrent_asc";
    String indexName = tableName + "_idx";

    try (Statement stmt = connection.createStatement()) {
      stmt.executeUpdate("CREATE TABLE " + tableName + "(v int)");
    }

    ExecutorService es = Executors.newFixedThreadPool(minThreads);
    List<Future<?>> futures = new ArrayList<>();
    CountDownLatch insertDone = new CountDownLatch(1);
    CountDownLatch backfillThreadStarted = new CountDownLatch(1);
    AtomicInteger numInserts = new AtomicInteger();
    ConnectionBuilder cb = newConnectionBuilder();

    futures.add(es.submit(() -> {
      try (Connection conn = cb.connect();
          Statement stmt = conn.createStatement()) {
        backfillThreadStarted.countDown();
        insertDone.await(AWAIT_TIMEOUT_SEC, TimeUnit.SECONDS);
        // This will wait for pg_index.indisready=true
        stmt.executeUpdate("CREATE INDEX " + indexName + " ON " + tableName + "(v ASC)");
      } catch (Exception ex) {
        LOG.error("CREATE INDEX thread failed", ex);
        fail("CREATE INDEX thread failed: " + ex.getMessage());
      }
    }));

    futures.add(es.submit(() -> {
      try (Connection conn = cb.connect();
          Statement stmt = conn.createStatement()) {
        backfillThreadStarted.await(AWAIT_TIMEOUT_SEC, TimeUnit.SECONDS);
        // Perform a chunk of N inserts until an index is fully constructed
        int v = 0;
        do {
          LOG.info("Inserting a chunk of " + insertsChunkSize + " values");
          for (int i = 0; i < insertsChunkSize; i++) {
            stmt.executeUpdate("INSERT INTO " + tableName + " VALUES (" + (v++) + ")");
            insertDone.countDown();
          }
        } while (!isIndexValid(conn, indexName));
        numInserts.set(v);
      } catch (Exception ex) {
        LOG.error("Insert thread failed", ex);
        fail("Insert thread failed: " + ex.getMessage());
      }
    }));

    // Wait for inserts/backfill to return
    try {
      LOG.info("Waiting for INSERT and CREATE INDEX threads");
      for (Future<?> future : futures) {
        future.get(AWAIT_TIMEOUT_SEC, TimeUnit.SECONDS);
      }
    } catch (TimeoutException ex) {
      LOG.warn("Threads info:\n\n" + ThreadUtil.getAllThreadsInfo());
      // It's very likely that cause lies on a YB side (e.g. unexpected performance slowdown),
      // not in test.
      fail("Waiting for future threads timed out, this is unexpected!");
    }

    // Make sure that index contains everything
    String countSql = "SELECT COUNT(*) FROM " + tableName + " WHERE v >= 0";
    assertTrue(doesUseIndex(countSql, indexName));
    try (Statement stmt = connection.createStatement()) {
      assertQuery(stmt, countSql, new Row(numInserts.get()));
    }
  }

  /** Inspect {@code pg_index.indisvalid} column for the given index */
  private boolean isIndexValid(Connection conn, String indexName) throws Exception {
    String selectIndexSql = "SELECT i.indisvalid FROM pg_index i"
        + " INNER JOIN pg_class c ON i.indexrelid = c.oid"
        + " WHERE c.relname = ?";
    try (PreparedStatement pstmt = conn.prepareStatement(selectIndexSql)) {
      pstmt.setString(1, indexName);
      List<Row> rows = getRowList(pstmt.executeQuery());
      if (!rows.isEmpty()) {
        return rows.get(0).getBoolean(0);
      } else {
        return false;
      }
    }
  }
}
