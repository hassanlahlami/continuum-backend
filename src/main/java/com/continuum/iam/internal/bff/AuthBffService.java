package com.continuum.iam.internal.bff;

import com.continuum.iam.internal.bff.AuthBffStore.AttemptRow;
import com.continuum.iam.internal.bff.AuthBffStore.SessionRow;
import com.continuum.iam.internal.bff.KeycloakTokenClient.TokenResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/** Login / session / logout logic of the BFF (ADR 0014). Tokens never leave the server. */
public class AuthBffService {

    public static final String IDENTIFIER = "IDENTIFIER";
    public static final String IP = "IP";

    /** A freshly opened session: the cookie value is returned once, only its hash is stored. */
    public record Opened(String cookieValue, SessionView view) {
    }

    public record SessionView(UUID tenantId, UUID userId, String displayName, Integer acr,
            OffsetDateTime expiresAt, int idleTimeoutSeconds) {
    }

    public sealed interface LoginOutcome {
        record Success(Opened opened) implements LoginOutcome {
        }

        record InvalidCredentials() implements LoginOutcome {
        }

        record RateLimited(long retryAfterSeconds) implements LoginOutcome {
        }
    }

    private final AuthBffStore store;
    private final KeycloakTokenClient keycloak;
    private final RefreshTokenCipher cipher;
    private final AuthBffProperties props;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public AuthBffService(AuthBffStore store, KeycloakTokenClient keycloak, RefreshTokenCipher cipher,
            AuthBffProperties props, Clock clock) {
        this.store = store;
        this.keycloak = keycloak;
        this.cipher = cipher;
        this.props = props;
        this.clock = clock;
    }

    public Optional<UUID> tenant(String entityCode) {
        return store.findActiveTenant(entityCode);
    }

    public String loginMode(UUID tenantId, String app) {
        // admin is never offered in REST mode (ADR 0014 §4: no acr >= 2 on direct grant)
        return "admin".equals(app) ? "redirect" : store.param(tenantId, "auth.login.mode").orElse("redirect");
    }

    public LoginOutcome login(String app, UUID tenantId, String username, String password, String ip) {
        OffsetDateTime now = now();
        String idKey = hash(tenantId + ":" + username.toLowerCase());
        String ipKey = hash(ip == null ? "unknown" : ip);
        long retry = Math.max(retryAfter(IDENTIFIER, idKey, now), retryAfter(IP, ipKey, now));
        if (retry > 0) {
            return new LoginOutcome.RateLimited(retry);
        }
        AuthBffProperties.Client client = props.apps().get(app);
        TokenResult token = keycloak.passwordGrant(client, username, password);
        if (token.invalidGrant()) {
            fail(IDENTIFIER, idKey, tenantId, "identifier", tenantId, now);
            fail(IP, ipKey, null, "ip", tenantId, now);
            long wait = Math.max(retryAfter(IDENTIFIER, idKey, now), retryAfter(IP, ipKey, now));
            return wait > 0 ? new LoginOutcome.RateLimited(wait) : new LoginOutcome.InvalidCredentials();
        }
        store.clearAttempt(IDENTIFIER, idKey);
        return new LoginOutcome.Success(open(tenantId, token));
    }

    private Opened open(UUID tenantId, TokenResult token) {
        JsonNode c = token.accessClaims();
        UUID userId = UUID.fromString(c.hasNonNull("continuum_user_id")
                ? c.get("continuum_user_id").asText() : c.get("sub").asText());
        OffsetDateTime now = now();
        int idle = intParam(tenantId, "auth.session.idle_timeout_seconds");
        OffsetDateTime absolute = now.plusSeconds(intParam(tenantId, "auth.session.absolute_timeout_seconds"));
        OffsetDateTime idleAt = min(now.plusSeconds(idle), absolute);
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String cookie = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String acr = c.hasNonNull("acr") ? c.get("acr").asText() : null;
        store.createSession(new SessionRow(hash(cookie), tenantId, userId, cipher.encrypt(token.refreshToken()), acr,
                absolute, idleAt));
        return new Opened(cookie, view(tenantId, userId, acr, absolute, idle));
    }

    /** Live session for a cookie value; slides the inactivity deadline (bounded by the absolute expiry). */
    public Optional<SessionView> current(String cookieValue) {
        if (cookieValue == null || cookieValue.isBlank()) {
            return Optional.empty();
        }
        OffsetDateTime now = now();
        String idHash = hash(cookieValue);
        return store.findLiveSession(idHash, now).map(s -> {
            int idle = intParam(s.tenantId(), "auth.session.idle_timeout_seconds");
            store.slide(idHash, min(now.plusSeconds(idle), s.expiresAt()));
            return view(s.tenantId(), s.userId(), s.acr(), s.expiresAt(), idle);
        });
    }

    public void logout(String app, String cookieValue) {
        String idHash = hash(cookieValue);
        store.findLiveSession(idHash, now()).ifPresent(s -> {
            keycloak.logout(props.apps().get(app), cipher.decrypt(s.refreshTokenEnc()));
            store.revoke(idHash, now());
        });
    }

    // ----- rate limiting (thresholds in DB, per tenant)

    private long retryAfter(String type, String key, OffsetDateTime now) {
        return store.attempt(type, key).map(AttemptRow::blockedUntil)
                .filter(b -> b != null && b.isAfter(now))
                .map(b -> Math.max(1, Duration.between(now, b).toSeconds())).orElse(0L);
    }

    private void fail(String type, String key, UUID rowTenant, String paramScope, UUID tenantId, OffsetDateTime now) {
        int max = intParam(tenantId, "auth.ratelimit." + paramScope + ".max_attempts");
        int window = intParam(tenantId, "auth.ratelimit." + paramScope + ".window_seconds");
        int block = intParam(tenantId, "auth.ratelimit.block_seconds");
        AttemptRow prev = store.attempt(type, key).orElse(null);
        boolean fresh = prev == null || prev.windowStart().plusSeconds(window).isBefore(now);
        int count = fresh ? 1 : prev.count() + 1;
        OffsetDateTime start = fresh ? now : prev.windowStart();
        OffsetDateTime blocked = count >= max ? now.plusSeconds(block) : (fresh ? null : prev.blockedUntil());
        store.saveAttempt(type, key, rowTenant, new AttemptRow(count, start, blocked));
    }

    private int intParam(UUID tenantId, String key) {
        return Integer.parseInt(store.param(tenantId, key)
                .orElseThrow(() -> new IllegalStateException("missing parameter " + key)));
    }

    private SessionView view(UUID tenantId, UUID userId, String acr, OffsetDateTime expiresAt, int idle) {
        Integer level = null;
        try {
            level = acr == null ? null : Integer.valueOf(acr.trim());
        } catch (NumberFormatException ignored) {
            // non numeric acr: unknown level
        }
        return new SessionView(tenantId, userId, store.username(tenantId, userId).orElse(null), level, expiresAt, idle);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private static OffsetDateTime min(OffsetDateTime a, OffsetDateTime b) {
        return a.isBefore(b) ? a : b;
    }

    public static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
