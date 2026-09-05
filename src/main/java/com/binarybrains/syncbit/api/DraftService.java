package com.binarybrains.syncbit.api;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * C5: the action review queue (decision 7 - draft-and-approve). approve()/reject() only
 * ever change status - there is no send path anywhere in this codebase.
 */
@Service
public class DraftService {

    private final JdbcTemplate jdbcTemplate;

    public DraftService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<DraftView> list(String status) {
        String sql = "SELECT * FROM draft" + (status == null ? "" : " WHERE status = ?") + " ORDER BY created_at DESC";
        Object[] params = status == null ? new Object[]{} : new Object[]{status};
        return jdbcTemplate.query(sql, (rs, rowNum) -> new DraftView(
                rs.getLong("draft_id"), rs.getString("tenant_id"), rs.getLong("signal_id"),
                rs.getString("recipient_vendor_id"), rs.getString("subject"), rs.getString("body"),
                rs.getString("evidence_ref"), rs.getString("status"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getString("approved_by"), rs.getObject("approved_at", LocalDateTime.class)),
                params);
    }

    public void approve(long draftId, String approvedBy) {
        jdbcTemplate.update(
                "UPDATE draft SET status = 'approved', approved_by = ?, approved_at = now() WHERE draft_id = ?",
                approvedBy, draftId);
    }

    public void reject(long draftId, String approvedBy) {
        jdbcTemplate.update(
                "UPDATE draft SET status = 'rejected', approved_by = ?, approved_at = now() WHERE draft_id = ?",
                approvedBy, draftId);
    }
}
