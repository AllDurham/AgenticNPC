package com.agenticnpc.audit;

/**
 * 审计日志持久化接口（MySQL 模式下双写用）。
 */
public interface AuditRepository {
    void save(AuditLogger.AuditEntry entry);
    void shutdown();
}
