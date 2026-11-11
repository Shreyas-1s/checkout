package com.boldfit.checkout.repository;

import com.boldfit.checkout.domain.ApprovedAction;
import com.boldfit.checkout.dto.Attention;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RecoveryRepository {
  private final JdbcTemplate db;

  public RecoveryRepository(JdbcTemplate db) {
    this.db = db;
  }

  public List<Attention> findAttention() {
    return db.query(
        "SELECT o.id,o.status,a.id AS attempt_id,COALESCE(d.exhausted,false)"
            + " exhausted,COALESCE(d.retries,0) retries,d.last_error FROM customer_order o LEFT"
            + " JOIN payment_attempt a ON a.order_id=o.id LEFT JOIN mock_delivery d ON"
            + " d.attempt_id=a.id WHERE o.status='PAYMENT_REVIEW' OR (d.exhausted=true AND"
            + " d.delivered=false AND o.status<>'CANCELLED') ORDER BY o.created_at",
        (rs, n) ->
            new Attention(
                rs.getObject("id", UUID.class),
                rs.getString("status"),
                rs.getObject("attempt_id", UUID.class),
                rs.getBoolean("exhausted"),
                rs.getInt("retries"),
                rs.getString("last_error")));
  }

  public List<UUID> lockAttemptOrder(UUID attemptId) {
    return db.queryForList(
        "SELECT o.id FROM customer_order o JOIN payment_attempt a ON a.order_id=o.id WHERE a.id=?"
            + " FOR UPDATE OF o",
        UUID.class,
        attemptId);
  }

  public List<ApprovedAction> findAction(UUID actionId) {
    return db.query(
        "SELECT action,order_id,attempt_id,reason,approval_status,approved_by,approved_at FROM"
            + " agent_action_audit WHERE action_id=?",
        (rs, n) ->
            new ApprovedAction(
                rs.getString("action"),
                rs.getObject("order_id", UUID.class),
                rs.getObject("attempt_id", UUID.class),
                rs.getString("reason"),
                rs.getString("approval_status"),
                rs.getString("approved_by"),
                rs.getTimestamp("approved_at").toInstant()),
        actionId);
  }

  public int requeueExhaustedDelivery(UUID attemptId) {
    return db.update(
        "UPDATE mock_delivery d SET exhausted=false,retries=0,last_error=NULL,next_delivery=now()"
            + " WHERE attempt_id=? AND delivered=false AND exhausted=true AND EXISTS(SELECT 1 FROM"
            + " payment_attempt a JOIN customer_order o ON o.id=a.order_id WHERE a.id=d.attempt_id"
            + " AND o.status<>'CANCELLED')",
        attemptId);
  }

  public int insertAction(
      UUID actionId,
      String action,
      UUID orderId,
      UUID attemptId,
      String reason,
      String approvalStatus,
      String approvedBy,
      java.sql.Timestamp approvedAt,
      String operator) {
    return db.update(
        "INSERT INTO"
            + " agent_action_audit(action_id,action,order_id,attempt_id,reason,approval_status,approved_by,approved_at,executed_by)"
            + " VALUES (?,?,?,?,?,?,?,?,?)",
        actionId,
        action,
        orderId,
        attemptId,
        reason,
        approvalStatus,
        approvedBy,
        approvedAt,
        operator);
  }
}
