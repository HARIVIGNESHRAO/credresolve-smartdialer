package com.credresolve.smartdialer.scheduler;

import com.credresolve.smartdialer.recovery.DispatchWorker;
import com.credresolve.smartdialer.safety.SafetyController;
import com.credresolve.smartdialer.service.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "smartdialer.scheduling", havingValue = "true", matchIfMissing = true)
public class DialerScheduler {
  private static final Logger log = LoggerFactory.getLogger(DialerScheduler.class);
  private final Database db;
  private final SafetyController safety;
  private final DispatchWorker worker;

  public DialerScheduler(Database db, SafetyController safety, DispatchWorker worker) {
    this.db = db;
    this.safety = safety;
    this.worker = worker;
  }

  @Scheduled(fixedDelay = 1000)
  public void tick() {
    try {
      for (var c : db.sql.queryForList("select id,mode from campaigns where status='RUNNING'"))
        safety.tick(Database.uuid(c.get("id")), c.get("mode").toString());
    } catch (RuntimeException e) {
      log.error("Dialer cycle failed; durable state remains available for retry", e);
    }
  }

  @Scheduled(fixedDelay = 500)
  public void callbacks() {
    try {
      worker.recoverAgents();
      worker.pollProvider();
    } catch (RuntimeException e) {
      log.error("Recovery/callback cycle failed", e);
    }
  }

  @Scheduled(fixedDelay = 250)
  public void dispatch() {
    try {
      for (int i = 0; i < 5 && worker.dispatchOne(); i++) {}
    } catch (RuntimeException e) {
      log.error("Dispatch cycle failed", e);
    }
  }
}
