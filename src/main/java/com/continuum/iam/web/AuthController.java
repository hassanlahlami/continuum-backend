package com.continuum.iam.web;

import com.continuum.ErrorReference;
import com.continuum.iam.internal.bff.AuthBffConfig;
import com.continuum.iam.internal.bff.AuthBffProperties;
import com.continuum.iam.internal.bff.AuthBffService;
import com.continuum.iam.internal.bff.AuthBffService.LoginOutcome;
import com.continuum.iam.internal.bff.AuthBffService.SessionView;
import gen.auth.api.LoginApi;
import gen.auth.api.OptionsApi;
import gen.auth.api.RedirectApi;
import gen.auth.api.SessionApi;
import gen.auth.model.AuthApp;
import gen.auth.model.LoginAuthenticated;
import gen.auth.model.LoginOptions;
import gen.auth.model.LoginRequest;
import gen.auth.model.LoginResult;
import gen.auth.model.MfaRequest;
import gen.auth.model.SessionState;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * BFF d'authentification (D41, ADR 0014, contrat {@code public/auth.yaml}) : le navigateur ne reçoit qu'un
 * cookie de session {@code HttpOnly}; les jetons Keycloak restent côté serveur. Tranche livrée : options,
 * session, login, logout. {@code /mfa}, {@code /authorize}, {@code /step-up} répondent 501 (suite).
 */
@RestController
@RequestMapping("/api/auth")
@ConditionalOnExpression(AuthBffConfig.ENABLED)
public class AuthController implements OptionsApi, SessionApi, LoginApi, RedirectApi {



    /** Problème d'authentification (reason du contrat). */
    static final class AuthProblemException extends RuntimeException {
        final HttpStatus status;
        final String reason;
        final String title;
        final Long retryAfter;

        AuthProblemException(HttpStatus status, String reason, String title, Long retryAfter) {
            super(reason);
            this.status = status;
            this.reason = reason;
            this.title = title;
            this.retryAfter = retryAfter;
        }
    }

    private final AuthBffService service;
    private final AuthBffProperties props;
    private final CsrfTokenRepository csrf;
    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private final String surface;

    public AuthController(AuthBffService service, AuthBffProperties props, CsrfTokenRepository csrf,
            HttpServletRequest request, HttpServletResponse response,
            @org.springframework.beans.factory.annotation.Value("${continuum.surface:all}") String surface) {
        this.surface = surface;
        this.service = service;
        this.props = props;
        this.csrf = csrf;
        this.request = request;
        this.response = response;
    }

    @Override
    public java.util.Optional<org.springframework.web.context.request.NativeWebRequest> getRequest() {
        return java.util.Optional.empty();
    }

    @Override
    public ResponseEntity<LoginOptions> getLoginOptions(AuthApp authApp, String entity) {
        String app = served(authApp);
        UUID tenant = service.tenant(entity).orElse(null);
        if (tenant == null) {
            throw new AuthProblemException(HttpStatus.NOT_FOUND, "unknown-entity", "Entité inconnue", null);
        }
        String mode = service.loginMode(tenant, app);
        return ResponseEntity.ok(new LoginOptions("rest".equals(mode) ? LoginOptions.ModeEnum.REST
                : LoginOptions.ModeEnum.REDIRECT, List.of(), List.of(LoginOptions.MfaMethodsEnum.OTP)));
    }

    @Override
    public ResponseEntity<SessionState> getSession(AuthApp authApp) {
        // le cookie XSRF-TOKEN est posé par le filtre CSRF de la chaîne /api/auth/**
        return ResponseEntity.ok(currentSession(served(authApp)).map(AuthController::state)
                .orElseGet(() -> new SessionState(false)));
    }

    @Override
    public ResponseEntity<LoginResult> login(String xsrf, LoginRequest body) {
        String app = served(body.getApp());
        String entity = body.getEntity() != null ? body.getEntity() : request.getParameter("entity");
        UUID tenant = service.tenant(entity).orElse(null);
        if (tenant == null) {
            // même réponse que des identifiants faux : pas d'énumération des entités
            throw invalidCredentials();
        }
        LoginOutcome outcome = service.login(app, tenant, body.getUsername(), body.getPassword(),
                request.getRemoteAddr());
        if (outcome instanceof LoginOutcome.RateLimited r) {
            throw new AuthProblemException(HttpStatus.TOO_MANY_REQUESTS, "rate-limited", "Trop de tentatives",
                    r.retryAfterSeconds());
        }
        if (outcome instanceof LoginOutcome.Success s) {
            setSessionCookie(app, s.opened().cookieValue(), false);
            // CSRF renouvelé après login
            csrf.saveToken(csrf.generateToken(request), request, response);
            return ResponseEntity.ok(new LoginAuthenticated("AUTHENTICATED",
                    state(s.opened().view())));
        }
        throw invalidCredentials();
    }

    @Override
    public ResponseEntity<LoginResult> verifyMfa(String xsrf, MfaRequest body) {
        throw new AuthProblemException(HttpStatus.NOT_IMPLEMENTED, "not-implemented", "Non implémenté", null);
    }

    @Override
    public ResponseEntity<Void> logout(String xsrf, AuthApp authApp) {
        String app = served(authApp);
        String cookie = cookieValue(app);
        if (currentSession(app).isEmpty()) {
            throw new AuthProblemException(HttpStatus.UNAUTHORIZED, "unauthenticated", "Authentification requise",
                    null);
        }
        service.logout(app, cookie);
        setSessionCookie(app, "", true);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> startRedirectLogin(String entity, AuthApp app, String returnTo, String idp) {
        served(app);
        throw new AuthProblemException(HttpStatus.NOT_IMPLEMENTED, "not-implemented", "Non implémenté", null);
    }

    @Override
    public ResponseEntity<Void> startStepUp(Integer acr, AuthApp app, String returnTo) {
        served(app);
        throw new AuthProblemException(HttpStatus.NOT_IMPLEMENTED, "not-implemented", "Non implémenté", null);
    }

    @ExceptionHandler(AuthProblemException.class)
    ResponseEntity<ProblemDetail> problem(AuthProblemException e) {
        ProblemDetail pd = ProblemDetail.forStatus(e.status);
        pd.setType(java.net.URI.create("https://continuum.local/problems/" + e.reason));
        pd.setTitle(e.title);
        pd.setProperty("reason", e.reason);
        pd.setProperty("traceId", ErrorReference.traceId());
        ResponseEntity.BodyBuilder b = ResponseEntity.status(e.status).contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (e.retryAfter != null) {
            b.header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfter));
        }
        return b.body(pd);
    }

    private static AuthProblemException invalidCredentials() {
        return new AuthProblemException(HttpStatus.UNAUTHORIZED, "invalid-credentials", "Identifiants invalides", null);
    }

    /** Front effectif (défaut app) ; 400 s'il n'est pas servi par {@code continuum.surface} (ADR 0014 avenant b). */
    private String served(AuthApp authApp) {
        String app = authApp == null ? AuthApp.APP.getValue() : authApp.getValue();
        if (!"all".equals(surface) && !surface.equals(app)) {
            throw new AuthProblemException(HttpStatus.BAD_REQUEST, "unsupported-app", "Front non servi", null);
        }
        return app;
    }

    private Optional<SessionView> currentSession(String app) {
        return service.current(cookieValue(app));
    }

    private String cookieValue(String app) {
        if (request.getCookies() != null) {
            String name = AuthBffProperties.cookieName(app);
            for (Cookie c : request.getCookies()) {
                if (name.equals(c.getName())) {
                    return c.getValue();
                }
            }
        }
        return null;
    }

    private void setSessionCookie(String app, String value, boolean expire) {
        ResponseCookie cookie = ResponseCookie.from(AuthBffProperties.cookieName(app), value)
                .httpOnly(true).secure(props.cookieSecure()).sameSite("Strict").path("/api")
                .maxAge(expire ? 0 : -1).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private static SessionState state(SessionView v) {
        return new SessionState(true).userId(v.userId()).displayName(v.displayName()).acr(v.acr())
                .expiresAt(v.expiresAt()).idleTimeoutSeconds(v.idleTimeoutSeconds());
    }
}
