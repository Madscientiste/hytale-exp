package com.madscientiste.rcon.application;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.logging.LogRecord;
import org.junit.Assume;

/** Collects every log record written while it is open, so tests can assert on the real log. */
public final class LogCapture implements AutoCloseable {

  // Loaded by name: the server jar is only on the test runtime classpath
  private static final String LOGGER_BACKEND =
      "com.hypixel.hytale.logger.backend.HytaleLoggerBackend";

  private final CopyOnWriteArrayList<LogRecord> records = new CopyOnWriteArrayList<>();
  private final boolean available;

  public LogCapture() {
    available = callBackend("subscribe");
  }

  /**
   * Skip the calling test when the server jar on the classpath has no way to listen to its log (an
   * older server generation). Tests that assert on the log call this first.
   */
  public void assumeAvailable() {
    Assume.assumeTrue(
        "SKIPPED: this test reads the server log, and this server jar does not expose its log"
            + " records",
        available);
  }

  /** False when the server jar cannot be listened to; log assertions must then be left out. */
  public boolean isAvailable() {
    return available;
  }

  public List<LogRecord> records() {
    return List.copyOf(records);
  }

  /** Forget what was captured so far. */
  public void clear() {
    records.clear();
  }

  public long count(Predicate<String> messageFilter) {
    return records.stream().filter(r -> messageFilter.test(String.valueOf(r.getMessage()))).count();
  }

  public boolean anyMessage(Predicate<String> messageFilter) {
    return count(messageFilter) > 0;
  }

  /** Number of records that carry an exception (a stack trace in the log file). */
  public long countWithStackTrace() {
    return records.stream().filter(r -> r.getThrown() != null).count();
  }

  /** Wait until a matching message shows up. Returns false if it does not within the timeout. */
  public boolean await(Predicate<String> messageFilter, long timeoutMs)
      throws InterruptedException {
    long end = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < end) {
      if (anyMessage(messageFilter)) {
        return true;
      }
      Thread.sleep(20);
    }
    return anyMessage(messageFilter);
  }

  @Override
  public void close() {
    if (available) {
      callBackend("unsubscribe");
    }
  }

  private boolean callBackend(String method) {
    try {
      Class.forName(LOGGER_BACKEND)
          .getMethod(method, CopyOnWriteArrayList.class)
          .invoke(null, records);
      return true;
    } catch (ReflectiveOperationException e) {
      return false;
    }
  }
}
