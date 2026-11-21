package in.toucan.monitor.backend.service;

import in.toucan.monitor.backend.domain.AuditLogEntity;
import in.toucan.monitor.backend.repo.Repos;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.Instant;

/** Audit trail for runs, exports and mapping confirmations. */
@Service
public class AuditService {

    private final Repos.AuditRepo repo;

    public AuditService(Repos.AuditRepo repo) {
        this.repo = repo;
    }

    public static String currentUser() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null ? "system" : a.getName();
    }

    public void log(String action, String details) {
        log(currentUser(), action, details);
    }

    public void log(String user, String action, String details) {
        AuditLogEntity e = new AuditLogEntity();
        e.at = Instant.now();
        e.username = user;
        e.action = action;
        e.details = details;
        repo.save(e);
    }
}
