package com.boldfit.checkout.repository;

import com.boldfit.checkout.domain.Delivery;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DeliveryRepository {
  private final JdbcTemplate db;

  public DeliveryRepository(JdbcTemplate db) {
    this.db = db;
  }

  public List<Delivery> findDueDeliveries() {
    return db.query(
        "SELECT attempt_id,payload FROM mock_delivery WHERE delivered=false AND exhausted=false AND"
            + " next_delivery<=now() ORDER BY next_delivery LIMIT 10",
        (rs, n) -> new Delivery(rs.getObject("attempt_id", UUID.class), rs.getString("payload")));
  }

  public int markDelivered(UUID attemptId) {
    return db.update("UPDATE mock_delivery SET delivered=true WHERE attempt_id=?", attemptId);
  }

  public List<Boolean> recordFailure(String reason, UUID attemptId) {
    return db.query(
        "UPDATE mock_delivery SET"
            + " retries=retries+1,last_error=?,exhausted=(retries+1>=5),next_delivery=now()+(LEAST(60,5*power(2,LEAST(retries,4)))"
            + " * interval '1 second') WHERE attempt_id=? AND delivered=false AND exhausted=false"
            + " RETURNING exhausted",
        (rs, n) -> rs.getBoolean("exhausted"),
        reason,
        attemptId);
  }
}
