package com.credresolve.smartdialer.service;

import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class Database {
  public final JdbcTemplate sql;
  private final TransactionTemplate tx;

  public Database(JdbcTemplate sql, PlatformTransactionManager manager) {
    this.sql = sql;
    tx = new TransactionTemplate(manager);
  }

  public <T> T tx(Supplier<T> work) {
    return tx.execute(s -> work.get());
  }

  public Map<String, Object> one(String query, Object... args) {
    var rows = sql.queryForList(query, args);
    if (rows.isEmpty()) throw new NoSuchElementException("Record not found");
    return rows.getFirst();
  }

  public int count(String query, Object... args) {
    return sql.queryForObject(query, Integer.class, args);
  }

  public Map<String, Object> lock(UUID campaign) {
    return one("select * from campaigns where id=? for update", campaign);
  }

  public static UUID uuid(Object value) {
    return value == null ? null : UUID.fromString(value.toString());
  }

  public static boolean terminal(String state) {
    return Set.of("COMPLETED", "FAILED", "CANCELLED").contains(state);
  }
}
