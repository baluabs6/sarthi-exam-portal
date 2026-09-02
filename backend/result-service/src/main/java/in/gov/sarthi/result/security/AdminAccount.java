package in.gov.sarthi.result.security;

/**
 * A named admin account with a role, replacing "everyone shares one
 * secret with no distinction." See AdminAccountRegistry for how
 * accounts are configured, and AdminAuthInterceptor for how roles are
 * enforced per-request.
 */
public record AdminAccount(String apiKey, String name, AdminRole role) {

    public enum AdminRole {
        /** Full read/write access to every admin endpoint. */
        SUPER_ADMIN,
        /** Can tune the live admission rate and view everything, but cannot touch grievances. */
        QUEUE_OPERATOR,
        /** Read-only everywhere — can view queue stats, grievances, audit logs, but never change anything. */
        AUDITOR
    }
}
