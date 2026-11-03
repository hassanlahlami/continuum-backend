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

        /** The account needs an OTP: answer {@code /mfa} with this challenge. */
        record MfaRequired(String challengeId) implements LoginOutcome {
        }

        /** The account has pending required actions: only the browser flow ({@code /authorize}) can complete them. */
        record ActionRequired() implements LoginOutcome {
        }

        /** {@code /mfa} with an unknown or expired challenge. */
        record ChallengeExpired() implements LoginOutcome {
        }
    }

    /** Redirect to Keycloak; {@code nonce} is the value to keep in the short-lived browser-binding cookie. */
    public record Redirect(String location, String nonce) {
    }

    /** End of a browser flow: {@code cookieValue} is null for a step-up (the existing session cookie stays). */
    public record Completed(String app, String cookieValue, SessionView view, String returnTo) {
    }

    /** Invalid redirect-flow input (bad {@code returnTo}, forged/expired {@code state}, refused code). */
    public static class BadFlowException extends RuntimeException {
        public BadFlowException(String message) {
            super(message);
        }
    }

    private record Challenge(String app, UUID tenantId, String username, String password, String idKey,
            OffsetDateTime expiresAt) {
    }

    private final java.util.concurrent.ConcurrentHashMap<String, Challenge> challenges =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();

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
            String d = token.errorDescription() == null ? "" : token.errorDescription().toLowerCase();
            if (d.contains("not fully set up")) {
                return new LoginOutcome.ActionRequired();
            }
            if (d.contains("otp")) {
                String id = randomToken(16);
                purgeChallenges(now);
                challenges.put(id, new Challenge(app, tenantId, username, password, idKey,
                        now.plusSeconds(optInt(tenantId, "auth.mfa.challenge_ttl_seconds", 300))));
                return new LoginOutcome.MfaRequired(id);
            }
            fail(IDENTIFIER, idKey, tenantId, "identifier", tenantId, now);
            fail(IP, ipKey, null, "ip", tenantId, now);
            long wait = Math.max(retryAfter(IDENTIFIER, idKey, now), retryAfter(IP, ipKey, now));
            return wait > 0 ? new LoginOutcome.RateLimited(wait) : new LoginOutcome.InvalidCredentials();
        }
        store.clearAttempt(IDENTIFIER, idKey);
        return new LoginOutcome.Success(open(app, tenantId, token));
    }

    /** Front remembered with a challenge (so {@code /mfa} needs no {@code app} parameter). */
    public Optional<String> challengeApp(String challengeId) {
        Challenge c = challengeId == null ? null : challenges.get(challengeId);
        return c == null || !c.expiresAt().isAfter(now()) ? Optional.empty() : Optional.of(c.app());
    }

    /** Answers the OTP challenge of a login; wrong code counts like a wrong password (same rate limit). */
    public LoginOutcome verifyMfa(String challengeId, String code, String ip) {
        OffsetDateTime now = now();
        Challenge c = challengeId == null ? null : challenges.get(challengeId);
        if (c == null || !c.expiresAt().isAfter(now)) {
            challenges.remove(challengeId == null ? "" : challengeId);
            return new LoginOutcome.ChallengeExpired();
        }
        String ipKey = hash(ip == null ? "unknown" : ip);
        long retry = Math.max(retryAfter(IDENTIFIER, c.idKey(), now), retryAfter(IP, ipKey, now));
        if (retry > 0) {
            return new LoginOutcome.RateLimited(retry);
        }
        TokenResult token = keycloak.passwordGrant(props.apps().get(c.app()), c.username(), c.password(), code);
        if (token.invalidGrant()) {
            fail(IDENTIFIER, c.idKey(), c.tenantId(), "identifier", c.tenantId(), now);
            fail(IP, ipKey, null, "ip", c.tenantId(), now);
            long wait = Math.max(retryAfter(IDENTIFIER, c.idKey(), now), retryAfter(IP, ipKey, now));
            if (wait > 0) {
                challenges.remove(challengeId);
                return new LoginOutcome.RateLimited(wait);
            }
            return new LoginOutcome.InvalidCredentials();
        }
        challenges.remove(challengeId);
        store.clearAttempt(IDENTIFIER, c.idKey());
        return new LoginOutcome.Success(open(c.app(), c.tenantId(), token));
    }

    private void purgeChallenges(OffsetDateTime now) {
        challenges.values().removeIf(c -> !c.expiresAt().isAfter(now));
    }

    // ----- browser flow terminated by the BFF (Code + PKCE confidential client), step-up

    /** {@code returnTo} must be an internal route (relative path); null means the root. */
    public static String checkReturnTo(String returnTo) {
        if (returnTo == null || returnTo.isEmpty()) {
            return "/";
        }
        if (!returnTo.startsWith("/") || returnTo.startsWith("//") || returnTo.contains("\\")
                || returnTo.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7f)) {
            throw new BadFlowException("returnTo must be an internal route");
        }
        return returnTo;
    }

    /** Starts the authorization-code flow; {@code acr} (nullable) is the level asked from Keycloak. */
    public Redirect startAuthorization(String app, UUID tenantId, String returnTo, String idp, Integer acr,
            String redirectUri) {
        return redirect(app, tenantId, checkReturnTo(returnTo), idp, acr, redirectUri, null);
    }

    /** Step-up of the live session behind {@code cookieValue}; empty when there is none for this front. */
    public Optional<Redirect> startStepUp(String app, String cookieValue, int acr, String returnTo,
            String redirectUri) {
        String rt = checkReturnTo(returnTo);
        if (cookieValue == null || cookieValue.isBlank()) {
            return Optional.empty();
        }
        String idHash = hash(cookieValue);
        return store.findLiveSession(idHash, now()).filter(s -> app.equals(s.app()))
                .map(s -> redirect(app, s.tenantId(), rt, null, acr, redirectUri, idHash));
    }

    private Redirect redirect(String app, UUID tenantId, String returnTo, String idp, Integer acr,
            String redirectUri, String stepUpSession) {
        String verifier = randomToken(32);
        String nonce = randomToken(16);
        java.util.Map<String, Object> st = new java.util.LinkedHashMap<>();
        st.put("a", app);
        st.put("t", tenantId.toString());
        st.put("r", returnTo);
        st.put("v", verifier);
        st.put("n", nonce);
        st.put("e", now().plusSeconds(optInt(tenantId, "auth.redirect.state_ttl_seconds", 600)).toEpochSecond());
        if (stepUpSession != null) {
            st.put("s", stepUpSession);
        }
        String state;
        try {
            state = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(cipher.encrypt(json.writeValueAsString(st)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        String challenge;
        try {
            challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        org.springframework.web.util.UriComponentsBuilder b = org.springframework.web.util.UriComponentsBuilder
                .fromUriString(keycloak.authorizationUri())
                .queryParam("client_id", props.apps().get(app).clientId())
                .queryParam("response_type", "code").queryParam("scope", "openid")
                .queryParam("redirect_uri", redirectUri).queryParam("state", state)
                .queryParam("code_challenge", challenge).queryParam("code_challenge_method", "S256");
        if (idp != null && !idp.isBlank()) {
            b.queryParam("kc_idp_hint", idp);
        }
        if (acr != null) {
            b.queryParam("acr_values", acr);
        }
        return new Redirect(b.build().encode().toUriString(), nonce);
    }

    /** Callback: checks the state and its browser binding, exchanges the code, opens or upgrades the session. */
    public Completed complete(String code, String state, String nonceCookie, String redirectUri) {
        JsonNode st;
        try {
            st = json.readTree(cipher.decrypt(Base64.getUrlDecoder().decode(state)));
        } catch (RuntimeException | java.io.IOException e) {
            throw new BadFlowException("invalid state");
        }
        if (st.path("e").asLong() < now().toEpochSecond()) {
            throw new BadFlowException("state expired");
        }
        if (nonceCookie == null || !MessageDigest.isEqual(nonceCookie.getBytes(StandardCharsets.UTF_8),
                st.path("n").asText().getBytes(StandardCharsets.UTF_8))) {
            throw new BadFlowException("state not bound to this browser");
        }
        String app = st.path("a").asText();
        UUID tenantId = UUID.fromString(st.path("t").asText());
        String returnTo = st.path("r").asText("/");
        AuthBffProperties.Client client = props.apps().get(app);
        if (client == null) {
            throw new BadFlowException("unknown front");
        }
        TokenResult token = keycloak.exchangeCode(client, code, st.path("v").asText(), redirectUri);
        if (token.invalidGrant()) {
            throw new BadFlowException("code refused");
        }
        if (st.hasNonNull("s")) {
            String idHash = st.get("s").asText();
            SessionRow s = store.findLiveSession(idHash, now())
                    .filter(r -> app.equals(r.app()) && tenantId.equals(r.tenantId()))
                    .orElseThrow(() -> new BadFlowException("session no longer live"));
            JsonNode c = token.accessClaims();
            String acr = c.hasNonNull("acr") ? c.get("acr").asText() : null;
            store.updateStepUp(idHash, cipher.encrypt(token.refreshToken()), acr);
            accessCache.remove(app + ":" + idHash);
            return new Completed(app, null, view(tenantId, s.userId(), acr, s.expiresAt(),
                    intParam(tenantId, "auth.session.idle_timeout_seconds")), returnTo);
        }
        Opened o = open(app, tenantId, token);
        return new Completed(app, o.cookieValue(), o.view(), returnTo);
    }

    private String randomToken(int bytes) {
        byte[] raw = new byte[bytes];
        random.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private int optInt(UUID tenantId, String key, int fallback) {
        return store.param(tenantId, key).map(Integer::parseInt).orElse(fallback);
    }

    private Opened open(String app, UUID tenantId, TokenResult token) {
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
        store.createSession(new SessionRow(hash(cookie), app, tenantId, userId, cipher.encrypt(token.refreshToken()), acr,
                absolute, idleAt));
        return new Opened(cookie, view(tenantId, userId, acr, absolute, idle));
    }

    /** Live session for a cookie value; slides the inactivity deadline (bounded by the absolute expiry). */
    public Optional<SessionView> current(String app, String cookieValue) {
        if (cookieValue == null || cookieValue.isBlank()) {
            return Optional.empty();
        }
        OffsetDateTime now = now();
        String idHash = hash(cookieValue);
        return store.findLiveSession(idHash, now).filter(s -> app.equals(s.app())).map(s -> {
            int idle = intParam(s.tenantId(), "auth.session.idle_timeout_seconds");
            store.slide(idHash, min(now.plusSeconds(idle), s.expiresAt()));
            return view(s.tenantId(), s.userId(), s.acr(), s.expiresAt(), idle);
        });
    }

    private record CachedAccess(String token, java.time.Instant validUntil) {
    }

    private final java.util.concurrent.ConcurrentHashMap<String, CachedAccess> accessCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Access token to present to the resource server for a live session (ADR 0014: tokens stay server-side).
     * Cached in memory until shortly before expiry, then renewed with the stored refresh token (rotation persisted).
     * Empty when the session is unknown/expired or Keycloak refuses the refresh.
     */
    public Optional<String> accessToken(String app, String cookieValue) {
        AuthBffProperties.Client client = props.apps().get(app);
        if (client == null || cookieValue == null || cookieValue.isBlank()) {
            return Optional.empty();
        }
        String idHash = hash(cookieValue);
        String cacheKey = app + ":" + idHash;
        OffsetDateTime now = now();
        // the session row names its front: a cookie never yields a token of another front's client
        Optional<SessionRow> session = store.findLiveSession(idHash, now).filter(r -> app.equals(r.app()));
        if (session.isEmpty()) {
            accessCache.remove(cacheKey);
            return Optional.empty();
        }
        SessionRow live = session.get();
        store.slide(idHash, min(now.plusSeconds(intParam(live.tenantId(), "auth.session.idle_timeout_seconds")),
                live.expiresAt()));
        CachedAccess hit = accessCache.get(cacheKey);
        if (hit != null && hit.validUntil().isAfter(java.time.Instant.now(clock))) {
            return Optional.of(hit.token());
        }
        synchronized (idHash.intern()) {
            hit = accessCache.get(cacheKey);
            if (hit != null && hit.validUntil().isAfter(java.time.Instant.now(clock))) {
                return Optional.of(hit.token());
            }
            SessionRow s = store.findLiveSession(idHash, now).filter(r -> app.equals(r.app())).orElse(null);
            if (s == null) {
                return Optional.empty();
            }
            KeycloakTokenClient.RefreshResult r = keycloak.refresh(client, cipher.decrypt(s.refreshTokenEnc()));
            if (r.invalidGrant()) {
                return Optional.empty();
            }
            store.updateRefreshToken(idHash, cipher.encrypt(r.refreshToken()));
            long ttl = Math.max(0, r.expiresInSeconds() - 15);
            accessCache.put(cacheKey, new CachedAccess(r.accessToken(), java.time.Instant.now(clock).plusSeconds(ttl)));
            return Optional.of(r.accessToken());
        }
    }

    public void logout(String app, String cookieValue) {
        String idHash = hash(cookieValue);
        accessCache.keySet().removeIf(k -> k.endsWith(":" + idHash));
        store.findLiveSession(idHash, now()).filter(s -> app.equals(s.app())).ifPresent(s -> {
            keycloak.logout(props.apps().get(s.app()), cipher.decrypt(s.refreshTokenEnc()));
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
