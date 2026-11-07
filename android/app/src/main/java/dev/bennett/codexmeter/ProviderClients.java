package dev.bennett.codexmeter;

import android.content.Context;
import android.os.Build;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The sign-in and usage routes for every provider added through the Add account picker.
 * Codex, Claude Code and Cursor keep their own classes and are not here.
 *
 * <p>Client ids, endpoints and headers are the ones each provider's own CLI or editor sends;
 * none of these usage routes is a documented public API. Providers marked
 * {@code inferred} in {@link ProviderCatalog} have a known sign-in flow, but whether that
 * token is accepted by the usage route has not been confirmed against a live account.
 */
public final class ProviderClients {
    private ProviderClients() {
    }

    /** The client for {@code provider}, or null for Codex, Claude Code and Cursor. */
    public static ProviderClient get(UsageProvider provider) {
        switch (provider) {
            case COPILOT:
                return new Copilot();
            case KIMI:
                return new Kimi();
            case FACTORY:
                return new Factory();
            case GROK:
                return new Grok();
            case ANTIGRAVITY:
                return new Antigravity();
            case DEVIN:
                return new Codeium(UsageProvider.DEVIN, "devin");
            case WINDSURF:
                return new Codeium(UsageProvider.WINDSURF, "windsurf");
            case AUGMENT:
                return new Augment();
            case CLINE:
                return new Cline();
            case OPENCODE_GO:
                return new OpenCodeGo();
            case MINIMAX:
                return new MiniMax();
            case ZAI:
                return new Zai();
            case KIRO:
                return new Kiro();
            case AMP:
                return new Amp();
            case OPENROUTER:
                return new OpenRouter();
            case KILO:
                return new Kilo();
            case CODEBUFF:
                return new Codebuff();
            case SYNTHETIC:
                return new KeyQuota(UsageProvider.SYNTHETIC, "https://api.synthetic.new/v2/quotas",
                        5 * ProviderUsageParsers.HOUR);
            case CHUTES:
                return new KeyQuota(UsageProvider.CHUTES,
                        "https://api.chutes.ai/users/me/subscription_usage",
                        ProviderUsageParsers.DAY);
            case V0:
                return new KeyQuota(UsageProvider.V0, "https://api.v0.dev/v1/rate-limits",
                        ProviderUsageParsers.DAY);
            case WARP:
                return new Warp();
            default:
                return null;
        }
    }

    // ================================================================ shared flows

    /** RFC 8628 device flow with form-encoded requests (GitHub, Kimi, xAI, WorkOS). */
    abstract static class StandardDevice extends ProviderClient {
        final String deviceUrl;
        final String tokenUrl;
        final String clientId;
        final String scope;

        StandardDevice(UsageProvider provider, String deviceUrl, String tokenUrl, String clientId,
                String scope) {
            super(provider);
            this.deviceUrl = deviceUrl;
            this.tokenUrl = tokenUrl;
            this.clientId = clientId;
            this.scope = scope;
        }

        ProviderHttp.Request decorate(ProviderHttp.Request request, Session session) {
            return request;
        }

        @Override
        public DeviceAuth.DeviceCode startDevice(Context context, Session session)
                throws Exception {
            ProviderHttp.Response response = ProviderHttp.send(context, provider.id + "_device",
                    decorate(ProviderHttp.post(deviceUrl).form(ProviderHttp.fields(
                            "client_id", clientId, "scope", scope)), session));
            DeviceAuth.DeviceCode code = DeviceAuth.parseDeviceCode(response.body);
            if (!response.ok() || !code.isValid()) {
                throw new Exception(OAuthClient.readError(response.body, provider.displayName
                        + " could not start sign-in (HTTP " + response.status + ")."));
            }
            session.device = code;
            return code;
        }

        @Override
        public DeviceAuth.Poll pollDevice(Context context, Session session) throws Exception {
            ProviderHttp.Response response = ProviderHttp.send(null, provider.id + "_poll",
                    decorate(ProviderHttp.post(tokenUrl).form(ProviderHttp.fields(
                            "grant_type", "urn:ietf:params:oauth:grant-type:device_code",
                            "device_code", session.device.deviceCode,
                            "client_id", clientId)), session));
            return DeviceAuth.parsePoll(response.status, response.body);
        }

        @Override
        public boolean canRefresh(AuthTokens tokens) {
            return !isApiKey(tokens) && !tokens.refreshToken.isEmpty()
                    && !tokens.refreshToken.equals(tokens.accessToken);
        }

        @Override
        public AuthTokens refresh(Context context, AuthTokens tokens) throws Exception {
            ProviderHttp.Response response = ProviderHttp.send(context,
                    provider.id + "_token_refresh", decorate(ProviderHttp.post(tokenUrl).form(
                            ProviderHttp.fields("grant_type", "refresh_token",
                                    "refresh_token", tokens.refreshToken,
                                    "client_id", clientId)), null));
            return mergeRefresh(response, tokens);
        }

        AuthTokens mergeRefresh(ProviderHttp.Response response, AuthTokens tokens)
                throws Exception {
            DeviceAuth.Tokens parsed = DeviceAuth.parseTokens(response.body);
            if (response.status == 400 || response.status == 401) {
                throw new ProviderClient.Unauthorized("Your " + provider.displayName
                        + " sign-in expired. Sign in again from Settings → Accounts.");
            }
            if (!response.ok() || parsed.accessToken.isEmpty()) {
                throw new Exception(OAuthClient.readError(response.body, provider.displayName
                        + " sign-in refresh failed (HTTP " + response.status + ")."));
            }
            return tokens.mergeRefresh(withJwtExpiry(parsed.toAuthTokens(now(), "")));
        }
    }

    /** Fills a missing expiry from the access token's JWT {@code exp} claim. */
    static AuthTokens withJwtExpiry(AuthTokens tokens) {
        if (tokens.expiresAtMillis > 0) return tokens;
        long exp = CursorApi.expiresAtMillis(tokens.accessToken);
        String email = tokens.email.isEmpty() ? CursorApi.email(tokens.accessToken) : tokens.email;
        return new AuthTokens(tokens.accessToken, tokens.refreshToken, tokens.idToken, exp,
                tokens.accountId, email);
    }

    // ================================================================ GitHub Copilot

    static final class Copilot extends StandardDevice {
        Copilot() {
            super(UsageProvider.COPILOT, "https://github.com/login/device/code",
                    "https://github.com/login/oauth/access_token", "Iv1.b507a08c87ecfe98",
                    "read:user");
        }

        @Override
        public AuthTokens finishDevice(Context context, Session session, DeviceAuth.Tokens tokens)
                throws Exception {
            String login = "";
            try {
                ProviderHttp.Response user = ProviderHttp.send(context, "copilot_user",
                        ProviderHttp.get("https://api.github.com/user")
                                .header("Authorization", "token " + tokens.accessToken));
                if (user.ok()) {
                    String email = user.json().optString("email", "");
                    login = email.contains("@") ? email : user.json().optString("login", "");
                }
            } catch (Exception ignored) {
                // The account name is a nicety; usage still works.
            }
            return new AuthTokens(tokens.accessToken, tokens.accessToken, "", 0L, "", login);
        }

        @Override
        public boolean canRefresh(AuthTokens tokens) {
            return false;
        }

        @Override
        public UsageSnapshot fetch(Context context, AuthTokens tokens) throws Exception {
            ProviderHttp.Response response = check(ProviderHttp.send(context, "copilot_usage",
                    ProviderHttp.get("https://api.github.com/copilot_internal/user")
                            .header("Authorization", "token " + tokens.accessToken)
                            .header("Editor-Version", "vscode/1.96.2")
                            .header("Editor-Plugin-Version", "copilot-chat/0.26.7")
                            .header("User-Agent", "GitHubCopilotChat/0.26.7")
                            .header("X-Github-Api-Version", "2025-04-01")), "usage");
            return nonEmpty(ProviderUsageParsers.copilot(response.body, now()));
        }
    }

    // ================================================================ Kimi Code

    static final class Kimi extends StandardDevice {
        Kimi() {
            super(UsageProvider.KIMI, "https://auth.kimi.com/api/oauth/device_authorization",
                    "https://auth.kimi.com/api/oauth/token",
                    "17e5f671-d194-4dfb-9706-5516cb48c098", null);
        }

        @Override
        ProviderHttp.Request decorate(ProviderHttp.Request request, Session session) {
            String deviceId = session == null ? "" : session.extra.optString("device_id", "");
            if (deviceId.isEmpty()) {
                deviceId = UUID.randomUUID().toString();
                if (session != null) {
                    try {
                        session.extra.put("device_id", deviceId);
                    } catch (Exception ignored) {
                        // JSONObject.put only throws for non-finite numbers.
                    }
                }
            }
            return request.header("X-Msh-Platform", "kimi_cli")
                    .header("X-Msh-Version", "1.0.0")
                    .header("X-Msh-Device-Name", "Codex Meter")
                    .header("X-Msh-Device-Model", Build.MODEL == null ? "Android" : Build.MODEL)
                    .header("X-Msh-Os-Version", "Android " + Build.VERSION.RELEASE)
                    .header("X-Msh-Device-Id", deviceId);
        }

        @Override
        public UsageSnapshot fetch(Context context, AuthTokens tokens) throws Exception {
            ProviderHttp.Response response = check(ProviderHttp.send(context, "kimi_usage",
                    ProviderHttp.get("https://api.kimi.com/coding/v1/usages")
                            .bearer(tokens.accessToken)), "usage");
            return nonEmpty(ProviderUsageParsers.kimi(response.body, now()));
        }
    }

    // ================================================================ WorkOS (Factory, Cline)

    static final String WORKOS_DEVICE = "https://api.workos.com/user_management/authorize/device";
    static final String WORKOS_TOKEN = "https://api.workos.com/user_management/authenticate";

    static final class Factory extends StandardDevice {
        Factory() {
            super(UsageProvider.FACTORY, WORKOS_DEVICE, WORKOS_TOKEN,
                    "client_01HNM792M5G5G1A2THWPXKFMXB", null);
        }

        @Override
        public AuthTokens finishDevice(Context context, Session session, DeviceAuth.Tokens tokens)
                throws Exception {
            return withJwtExpiry(tokens.toAuthTokens(now(), ""));
        }

        @Override
        public AuthTokens refresh(Context context, AuthTokens tokens) throws Exception {
            JSONObject body = new JSONObject()
                    .put("client_id", clientId)
                    .put("grant_type", "refresh_token")
                    .put("refresh_token", tokens.refreshToken);
            return mergeRefresh(ProviderHttp.send(context, "factory_token_refresh",
                    ProviderHttp.post(WORKOS_TOKEN).json(body)), tokens);
        }

        private ProviderHttp.Request factory(ProviderHttp.Request request, AuthTokens tokens) {
            return request.bearer(tokens.accessToken)
                    .header("x-factory-client", "web-app")
                    .header("Origin", "https://app.factory.ai")
                    .header("Referer", "https://app.factory.ai/");
        }

        @Override
        public UsageSnapshot fetch(Context context, AuthTokens tokens) throws Exception {
            String plan = "";
            try {
                ProviderHttp.Response me = ProviderHttp.send(context, "factory_profile",
                        factory(ProviderHttp.get("https://app.factory.ai/api/app/auth/me"),
                                tokens));
                if (me.ok()) {
                    plan = ProviderUsageParsers.factoryPlan(me.body);
                    String email = ProviderUsageParsers.factoryEmail(me.body);
                    if (!email.isEmpty()) ProviderPreferences.setEmail(context, provider, email);
                }
            } catch (Exception ignored) {
                // Plan and email are optional.
            }
            ProviderHttp.Response limits = ProviderHttp.send(context, "factory_usage",
                    factory(ProviderHttp.get("https://api.factory.ai/api/billing/limits"),
                            tokens));
            if (limits.status == 404) {
                limits = ProviderHttp.send(context, "factory_usage_legacy", factory(
                        ProviderHttp.get("https://app.factory.ai/api/organization/subscription/"
                                + "usage?useCache=true"), tokens));
            }
            check(limits, "usage");
            return nonEmpty(ProviderUsageParsers.factory(limits.body, plan, now()));
        }
    }

    static final class Cline extends StandardDevice {
        Cline() {
            super(UsageProvider.CLINE, WORKOS_DEVICE, WORKOS_TOKEN,
                    "client_01K3A541FN8TA3EPPHTD2325AR", null);
        }

        /** Cline swaps the WorkOS session for its own tokens. */
        @Override
        public AuthTokens finishDevice(Context context, Session session, DeviceAuth.Tokens tokens)
                throws Exception {
            JSONObject body = new JSONObject()
                    .put("accessToken", tokens.accessToken)
                    .put("refreshToken", tokens.refreshToken);
            ProviderHttp.Response response = ProviderHttp.send(context, "cline_register",
                    ProviderHttp.post("https://api.cline.bot/api/v1/auth/register").json(body));
            if (!response.ok()) {
                // Fall back to the WorkOS token; the usage call retries it with Cline's prefix.
                return withJwtExpiry(tokens.toAuthTokens(now(), "workos"));
            }
            return withJwtExpiry(DeviceAuth.parseTokens(response.body).toAuthTokens(now(), ""));
        }

        @Override
        public AuthTokens refresh(Context context, AuthTokens tokens) throws Exception {
            if ("workos".equals(tokens.accountId)) {
                return mergeRefresh(ProviderHttp.send(context, "cline_workos_refresh",
                        ProviderHttp.post(WORKOS_TOKEN).json(new JSONObject()
                                .put("client_id", clientId)
                                .put("grant_type", "refresh_token")
                                .put("refresh_token", tokens.refreshToken))), tokens);
            }
            JSONObject body = new JSONObject()
                    .put("refreshToken", tokens.refreshToken)
                    .put("grantType", "refresh_token");
            return mergeRefresh(ProviderHttp.send(context, "cline_token_refresh",
                    ProviderHttp.post("https://api.cline.bot/api/v1/auth/refresh").json(body)),
                    tokens);
        }

        @Override
        public UsageSnapshot fetch(Context context, AuthTokens tokens) throws Exception {
            String url = "https://api.cline.bot/api/v1/users/me/plan/usage-limits";
            ProviderHttp.Response response = ProviderHttp.send(context, "cline_usage",
                    ProviderHttp.get(url).bearer(tokens.accessToken));
            if (response.status == 401 && !tokens.accessToken.startsWith("workos:")) {
                response = ProviderHttp.send(context, "cline_usage_workos",
                        ProviderHttp.get(url).bearer("workos:" + tokens.accessToken));
            }
            check(response, "usage");
            return nonEmpty(ProviderUsageParsers.cline(response.body, now()));
        }
    }

    // ================================================================ Grok

    static final class Grok extends StandardDevice {
        Grok() {
            super(UsageProvider.GROK, "https://auth.x.ai/oauth2/device/code",
                    "https://auth.x.ai/oauth2/token", "b1a00492-073a-47ea-816f-4c329264a828",
                    "openid profile email offline_access grok-cli:access api:access");
        }

        @Override
        public AuthTokens finishDevice(Context context, Session session, DeviceAuth.Tokens tokens)
                throws Exception {
            AuthTokens stored = withJwtExpiry(tokens.toAuthTokens(now(), ""));
            if (stored.email.isEmpty() && !tokens.idToken.isEmpty()) {
                String email = CursorApi.email(tokens.idToken);
                stored = new AuthTokens(stored.accessToken, stored.refreshToken, stored.idToken,
                        stored.expiresAtMillis, stored.accountId, email);
            }
            return stored;
        }

        @Override
        public UsageSnapshot fetch(Context context, AuthTokens tokens) throws Exception {
            ProviderHttp.Response response = check(ProviderHttp.send(context, "grok_usage",
                    ProviderHttp.get("https://cli-chat-proxy.grok.com/v1/billing?format=credits")
                            .bearer(tokens.accessToken)
                            .header("x-xai-token-auth", "xai-grok-cli")), "usage");
            return nonEmpty(ProviderUsageParsers.grok(response.body, now()));
        }
    }

    // ================================================================ Antigravity

    static final class Antigravity extends ProviderClient {
        // Supplied at build time (ANTIGRAVITY_CLIENT_ID / ANTIGRAVITY_CLIENT_SECRET).
        static final String CLIENT_ID = BuildConfig.ANTIGRAVITY_CLIENT_ID;
        static final String CLIENT_SECRET = BuildConfig.ANTIGRAVITY_CLIENT_SECRET;

        static boolean configured() {
            return !CLIENT_ID.isEmpty() && !CLIENT_SECRET.isEmpty();
        }
        static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
        static final String[] HOSTS = {"https://cloudcode-pa.googleapis.com",
                "https://daily-cloudcode-pa.googleapis.com"};

        Antigravity() {
            super(UsageProvider.ANTIGRAVITY);
        }

        @Override
        public String loopbackAuthUrl(String redirectUri, Session session) throws Exception {
            if (!configured()) {
                throw new java.io.IOException(
                        "Antigravity sign-in isn't set up in this build of Codex Meter.");
            }
            return "https://accounts.google.com/o/oauth2/v2/auth?" + ProviderHttp.query(
                    "client_id", CLIENT_ID,
                    "redirect_uri", redirectUri,
                    "response_type", "code",
                    "scope", "https://www.googleapis.com/auth/cloud-platform "
                            + "https://www.googleapis.com/auth/userinfo.email",
                    "access_type", "offline",
                    "prompt", "select_account consent",
                    "code_challenge", session.challenge,
                    "code_challenge_method", "S256",
                    "state", session.state);
        }

        @Override
        public AuthTokens finishLoopback(Context context, Session session,
                Map<String, String> parameters, String redirectUri) throws Exception {
            ProviderHttp.Response response = ProviderHttp.send(context, "antigravity_exchange",
                    ProviderHttp.post(TOKEN_URL).form(ProviderHttp.fields(
                            "code", parameters.get("code"),
                            "client_id", CLIENT_ID,
                            "client_secret", CLIENT_SECRET,
                            "redirect_uri", redirectUri,
                            "grant_type", "authorization_code",
                            "code_verifier", session.verifier)));
            DeviceAuth.Tokens tokens = DeviceAuth.parseTokens(response.body);
            if (!response.ok() || tokens.accessToken.isEmpty()) {
                throw new Exception(OAuthClient.readError(response.body,
                        "Google sign-in failed (HTTP " + response.status + ")."));
            }
            String email = "";
            try {
                ProviderHttp.Response info = ProviderHttp.send(context, "antigravity_userinfo",
                        ProviderHttp.get("https://www.googleapis.com/oauth2/v2/userinfo")
                                .bearer(tokens.accessToken));
                if (info.ok()) email = info.json().optString("email", "");
            } catch (Exception ignored) {
                // Optional.
            }
            AuthTokens stored = tokens.toAuthTokens(now(), "");
            return new AuthTokens(stored.accessToken, stored.refreshToken, stored.idToken,
                    stored.expiresAtMillis, "", email);
        }

        @Override
        public boolean canRefresh(AuthTokens tokens) {
            return !tokens.refreshToken.isEmpty()
                    && !tokens.refreshToken.equals(tokens.accessToken);
        }

        @Override
        public AuthTokens refresh(Context context, AuthTokens tokens) throws Exception {
            ProviderHttp.Response response = ProviderHttp.send(context,
                    "antigravity_token_refresh", ProviderHttp.post(TOKEN_URL).form(
                            ProviderHttp.fields("grant_type", "refresh_token",
                                    "refresh_token", tokens.refreshToken,
                                    "client_id", CLIENT_ID,
                                    "client_secret", CLIENT_SECRET)));
            DeviceAuth.Tokens parsed = DeviceAuth.parseTokens(response.body);
            if (response.status == 400 || response.status == 401) {
                throw new Unauthorized("Your Google sign-in for Antigravity expired. "
                        + "Sign in again from Settings → Accounts.");
            }
            if (!response.ok() || parsed.accessToken.isEmpty()) {
                throw new Exception("Google sign-in refresh failed (HTTP " + response.status
                        + ").");
            }
            return tokens.mergeRefresh(new AuthTokens(parsed.accessToken, "", "",
                    now() + Math.max(60L, parsed.expiresInSeconds) * 1000L, "", ""));
        }

        @Override
        public UsageSnapshot fetch(Context context, AuthTokens tokens) throws Exception {
            String plan = "";
            Exception last = null;
            for (String host : HOSTS) {
                try {
                    ProviderHttp.Response assist = ProviderHttp.send(context,
                            "antigravity_plan", ProviderHttp.post(host
                                    + "/v1internal:loadCodeAssist").bearer(tokens.accessToken)
                                    .header("User-Agent", "antigravity/2.24.0 (aidev_client; "
                                            + "os_type=darwin; arch=arm64)")
                                    .json(new JSONObject().put("metadata", new JSONObject()
                                            .put("ideType", "ANTIGRAVITY")
                                            .put("platform", "PLATFORM_UNSPECIFIED")
                                            .put("pluginType", "GEMINI"))));
                    if (assist.status == 401) check(assist, "plan");
                    if (assist.ok()) plan = ProviderUsageParsers.antigravityPlan(assist.body);
                    ProviderHttp.Response quota = check(ProviderHttp.send(context,
                            "antigravity_usage", ProviderHttp.post(host
                                    + "/v1internal:retrieveUserQuotaSummary")
                                    .bearer(tokens.accessToken)
                                    .header("User-Agent", "antigravity")
                                    .json(new JSONObject())), "usage");
                    return nonEmpty(ProviderUsageParsers.antigravity(quota.body, plan, now()));
                } catch (Unauthorized unauthorized) {
                    throw unauthorized;
                } catch (Exception exception) {
                    last = exception;
                }
            }
            throw last == null ? new Exception("Antigravity usage is unavailable.") : last;
        }
    }

    // ================================================================ Devin and Windsurf

    static final class Codeium extends ProviderClient {
        final String ideName;

        Codeium(UsageProvider provider, String ideName) {
            super(provider);
            this.ideName = ideName;
        }

        @Override
        public String pasteUrl(Session session) {
            return "https://www.codeium.com/profile?" + ProviderHttp.query(
                    "response_type", "token",
                    "redirect_uri", "vim-show-auth-token",
                    "state", session.state,
                    "scope", "openid profile email",
                    "redirect_parameters_type", "query");
        }

        @Override
        public String pasteHint() {
            return "Sign in with your " + provider.displayName
                    + " account, then copy the token the page shows and paste it here.";
        }

        @Override
        public AuthTokens finishPaste(Context context, Session session, String pasted)
                throws Exception {
            String token = DeviceAuth.extractPasted(pasted, "access_token", "token",
                    "auth_token", "firebase_id_token");
            if (token.isEmpty()) throw new Exception("Paste the token first.");
            ProviderHttp.Response response = ProviderHttp.send(context,
                    provider.id + "_register", ProviderHttp.post(
                            "https://api.codeium.com/register_user/").json(new JSONObject()
                                    .put("firebase_id_token", token)));
            String apiKey = response.json().optString("api_key", "");
            if (!response.ok() || apiKey.isEmpty()) {
                throw new Exception(OAuthClient.readError(response.body,
                        "That token wasn't accepted. Copy it again and paste the whole thing."));
            }
            String name = response.json().optString("name", "");
            AuthTokens tokens = new AuthTokens(apiKey, apiKey, "", 0L, API_KEY_ACCOUNT, name);
            fetch(context, tokens);
            return tokens;
        }

        @Override
        public UsageSnapshot fetch(Context context, AuthTokens tokens) throws Exception {
            JSONObject metadata = new JSONObject()
                    .put("apiKey", tokens.accessToken)
                    .put("ideName", ideName)
                    .put("ideVersion", "1.108.2")
                    .put("extensionName", ideName)
                    .put("extensionVersion", "1.108.2")
                    .put("locale", "en");
            ProviderHttp.Response response = check(ProviderHttp.send(context,
                    provider.id + "_usage", ProviderHttp.post("https://server.codeium.com/"
                            + "exa.seat_management_pb.SeatManagementService/GetUserStatus")
                            .header("Connect-Protocol-Version", "1")
                            .json(new JSONObject().put("metadata", metadata))), "usage");
            return nonEmpty(ProviderUsageParsers.codeium(response.body, now()));
        }
    }

    // ================================================================ Augment

    static final class Augment extends ProviderClient {
        static final String CLIENT_ID = "auggie-cli-json-paste";

        Augment() {
            super(UsageProvider.AUGMENT);
        }

        @Override
        public String pasteUrl(Session session) {
            return "https://auth.augmentcode.com/authorize?" + ProviderHttp.query(
                    "response_type", "code",
                    "code_challenge", session.challenge,
                    "code_challenge_method", "S256",
                    "client_id", CLIENT_ID,
                    "state", session.state,
                    "prompt", "login");
        }

        @Override
        public String pasteHint() {
            return "After you sign in, Augment shows a short block of text that starts with "
                    + "{\"code\". Copy all of it and paste it here.";
        }

        @Override
        public AuthTokens finishPaste(Context context, Session session, String pasted)
                throws Exception {
            JSONObject reply = DeviceAuth.json(pasted == null ? "" : pasted.trim());
            String code = reply.optString("code", "");
            String state = reply.optString("state", "");
            String tenant = reply.optString("tenant_url", "");
            if (code.isEmpty() || tenant.isEmpty()) {
                throw new Exception("Paste the whole text block Augment shows, including the "
                        + "braces.");
            }
            if (!state.isEmpty() && !state.equals(session.state)) {
                throw new Exception("That code is from a different sign-in. Open the page again.");
            }
            String host = URI.create(tenant).getHost();
            if (host == null || !host.toLowerCase(Locale.ROOT).endsWith(".augmentcode.com")
                    || !tenant.startsWith("https://")) {
                throw new Exception("That sign-in points to an unexpected server, so it was "
                        + "not used.");
            }
            if (!tenant.endsWith("/")) tenant = tenant + "/";
            ProviderHttp.Response response = ProviderHttp.send(context, "augment_exchange",
                    ProviderHttp.post(tenant + "token").json(new JSONObject()
                            .put("grant_type", "authorization_code")
                            .put("client_id", CLIENT_ID)
                            .put("code_verifier", session.verifier)
                            .put("redirect_uri", "")
                            .put("code", code)));
            DeviceAuth.Tokens tokens = DeviceAuth.parseTokens(response.body);
            if (!response.ok() || tokens.accessToken.isEmpty()) {
                throw new Exception(OAuthClient.readError(response.body,
                        "Augment sign-in failed (HTTP " + response.status + ")."));
            }
            AuthTokens stored = new AuthTokens(tokens.accessToken, tokens.accessToken, "", 0L,
                    tenant, tokens.email);
            fetch(context, stored);
            return stored;
        }

        @Override
        public UsageSnapshot fetch(Context context, AuthTokens tokens) throws Exception {
            String tenant = tokens.accountId;
            if (!tenant.startsWith("https://")) {
                throw new Unauthorized("Sign in to Augment again from Settings → Accounts.");
            }
            ProviderHttp.Response response = check(ProviderHttp.send(context, "augment_usage",
                    ProviderHttp.post(tenant + "get-billing-summary").bearer(tokens.accessToken)
                            .json(new JSONObject())), "usage");
            return nonEmpty(ProviderUsageParsers.augment(response.body, now()));
        }
    }

    // ================================================================ OpenCode Go

    static final class OpenCodeGo extends ProviderClient {
        static final String BASE = "https://opencode.ai/console/auth/device/";

        OpenCodeGo() {
            super(UsageProvider.OPENCODE_GO);
        }

        @Override
        public DeviceAuth.DeviceCode startDevice(Context context, Session session)
                throws Exception {
            ProviderHttp.Response response = ProviderHttp.send(context, "opencode_device",
                    ProviderHttp.post(BASE + "code").json(new JSONObject()
                            .put("client_id", "opencode-cli")
                            .put("supports_org_scope", true)));
            DeviceAuth.DeviceCode code = DeviceAuth.parseDeviceCode(response.body);
            if (!response.ok() || !code.isValid()) {
                throw new Exception(OAuthClient.readError(response.body,
                        "OpenCode could not start sign-in (HTTP " + response.status + ")."));
            }
            session.device = code;
            return code;
        }

        @Override
        public DeviceAuth.Poll pollDevice(Context context, Session session) throws Exception {
            ProviderHttp.Response response = ProviderHttp.send(null, "opencode_poll",
                    ProviderHttp.post(BASE + "token").json(new JSONObject()
                            .put("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                            .put("device_code", session.device.deviceCode)
                            .put("client_id", "opencode-cli")));
            return DeviceAuth.parsePoll(response.status, response.body);
        }

        @Override
        public AuthTokens finishDevice(Context context, Session session, DeviceAuth.Tokens tokens) {
            return withJwtExpiry(tokens.toAuthTokens(now(), ""));
        }

        @Override
        public boolean canRefresh(AuthTokens tokens) {
            return !isApiKey(tokens) && !tokens.refreshToken.equals(tokens.accessToken);
        }

        @Override
        public AuthTokens refresh(Context context, AuthTokens tokens) throws Exception {
            ProviderHttp.Response response = ProviderHttp.send(context, "opencode_refresh",
                    ProviderHttp.post(BASE + "token").json(new JSONObject()
                            .put("grant_type", "refresh_token")
                            .put("refresh_token", tokens.refreshToken)
                            .put("client_id", "opencode-cli")));
            DeviceAuth.Tokens parsed = DeviceAuth.parseTokens(response.body);
            if (!response.ok() || parsed.accessToken.isEmpty()) {
                throw new Unauthorized("Your OpenCode sign-in expired. Sign in again.");
            }
            return tokens.mergeRefresh(withJwtExpiry(parsed.toAuthTokens(now(), "")));
        }

        @Override
        public UsageSnapshot fetch(Context context, AuthTokens tokens) throws Exception {
            ProviderHttp.Response response = check(ProviderHttp.send(context, "opencode_usage",
                    ProviderHttp.get("https://opencode.ai/zen/go/v1/usage")
                            .bearer(tokens.accessToken)), "usage");
            return nonEmpty(ProviderUsageParsers.opencodeGo(response.body, now()));
        }
    }

    // ================================================================ MiniMax

    static final class MiniMax extends ProviderClient {
        static final String CLIENT_ID = "78257093-7e40-4613-99e0-527b14b39113";
        static final String ACCOUNT = "https://account.minimax.io/oauth2/";

        MiniMax() {
            super(UsageProvider.MINIMAX);
        }

        @Override
        public DeviceAuth.DeviceCode startDevice(Context context, Session session)
                throws Exception {
            ProviderHttp.Response response = ProviderHttp.send(context, "minimax_device",
                    ProviderHttp.post(ACCOUNT + "device/code").form(ProviderHttp.fields(
                            "response_type", "code",
                            "client_id", CLIENT_ID,
                            "scope", "group_id profile model.completion",
                            "code_challenge", session.challenge,
                            "code_challenge_method", "S256",
                            "state", session.state)));
            DeviceAuth.DeviceCode parsed = DeviceAuth.parseDeviceCode(response.body);
            // MiniMax polls with the user code itself.
            DeviceAuth.DeviceCode code = new DeviceAuth.DeviceCode(
                    parsed.deviceCode.isEmpty() ? parsed.userCode : parsed.deviceCode,
                    parsed.userCode, parsed.verificationUri, parsed.verificationUriComplete,
                    parsed.intervalSeconds, parsed.expiresInSeconds);
            if (!response.ok() || !code.isValid()) {
                throw new Exception(OAuthClient.readError(response.body,
                        "MiniMax could not start sign-in (HTTP " + response.status + ")."));
            }
            session.device = code;
            return code;
        }

        @Override
        public DeviceAuth.Poll pollDevice(Context context, Session session) throws Exception {
            ProviderHttp.Response response = ProviderHttp.send(null, "minimax_poll",
                    ProviderHttp.post(ACCOUNT + "token").form(ProviderHttp.fields(
                            "grant_type", "urn:ietf:params:oauth:grant-type:user_code",
                            "client_id", CLIENT_ID,
                            "user_code", session.device.userCode,
                            "code_verifier", session.verifier)));
            return DeviceAuth.parsePoll(response.status, response.body);
        }

        @Override
        public boolean canRefresh(AuthTokens tokens) {
            return !isApiKey(tokens) && !tokens.refreshToken.equals(tokens.accessToken);
        }

        @Override
        public AuthTokens refresh(Context context, AuthTokens tokens) throws Exception {
            ProviderHttp.Response response = ProviderHttp.send(context, "minimax_refresh",
                    ProviderHttp.post(ACCOUNT + "token").form(ProviderHttp.fields(
                            "grant_type", "refresh_token",
                            "client_id", CLIENT_ID,
                            "refresh_token", tokens.refreshToken)));
            DeviceAuth.Tokens parsed = DeviceAuth.parseTokens(response.body);
            if (!response.ok() || parsed.accessToken.isEmpty()) {
                throw new Unauthorized("Your MiniMax sign-in expired. Sign in again.");
            }
            return tokens.mergeRefresh(parsed.toAuthTokens(now(), ""));
        }

        @Override
        public UsageSnapshot fetch(Context context, AuthTokens tokens) throws Exception {
            ProviderHttp.Response response = ProviderHttp.send(context, "minimax_usage",
                    ProviderHttp.get("https://api.minimax.io/v1/token_plan/remains")
                            .bearer(tokens.accessToken));
            if (response.status == 404) {
                response = ProviderHttp.send(context, "minimax_usage_legacy", ProviderHttp.get(
                        "https://api.minimax.io/v1/api/openplatform/coding_plan/remains")
                        .bearer(tokens.accessToken));
            }
            check(response, "usage");
            JSONObject base = response.json().optJSONObject("base_resp");
            if (base != null && base.optInt("status_code", 0) == 1004) {
                throw new Unauthorized("MiniMax rejected the saved sign-in.");
            }
            return nonEmpty(ProviderUsageParsers.minimax(response.body, now()));
        }
    }

    // ================================================================ Z.ai

    static final class Zai extends ProviderClient {
        Zai() {
            super(UsageProvider.ZAI);
        }

        @Override
        public UsageSnapshot fetch(Context context, AuthTokens tokens) throws Exception {
            String url = "https://api.z.ai/api/monitor/usage/quota/limit";
            ProviderHttp.Response response = ProviderHttp.send(context, "zai_usage",
                    ProviderHttp.get(url).bearer(tokens.accessToken));
            if (response.status == 401) {
                response = ProviderHttp.send(context, "zai_usage_raw", ProviderHttp.get(url)
                        .header("Authorization", tokens.accessToken));
            }
            check(response, "usage");
            JSONObject root = response.json();
            if (root.has("success") && !root.optBoolean("success", true)) {
                int code = root.optInt("code", 0);
                if (code == 401 || code == 1001 || code == 1002) {
                    throw new Unauthorized("Z.ai rejected this API key.");
                }
                throw new Exception(OAuthClient.readError(response.body,
                        "Z.ai returned an error (code " + code + ")."));
            }
            return nonEmpty(ProviderUsageParsers.zai(response.body, now()));
        }
    }

    // ================================================================ Kiro (AWS Builder ID)

    static final class Kiro extends ProviderClient {
        static final String OIDC = "https://oidc.us-east-1.amazonaws.com/";

        Kiro() {
            super(UsageProvider.KIRO);
        }

        @Override
        public DeviceAuth.DeviceCode startDevice(Context context, Session session)
                throws Exception {
            ProviderHttp.Response client = ProviderHttp.send(context, "kiro_register",
                    ProviderHttp.post(OIDC + "client/register").json(new JSONObject()
                            .put("clientName", "Codex Meter")
                            .put("clientType", "public")
                            .put("scopes", new JSONArray()
                                    .put("codewhisperer:completions")
                                    .put("codewhisperer:analysis")
                                    .put("codewhisperer:conversations"))));
            String clientId = client.json().optString("clientId", "");
            String clientSecret = client.json().optString("clientSecret", "");
            if (!client.ok() || clientId.isEmpty()) {
                throw new Exception("AWS could not start sign-in (HTTP " + client.status + ").");
            }
            session.extra.put("clientId", clientId).put("clientSecret", clientSecret);
            ProviderHttp.Response response = ProviderHttp.send(context, "kiro_device",
                    ProviderHttp.post(OIDC + "device_authorization").json(new JSONObject()
                            .put("clientId", clientId)
                            .put("clientSecret", clientSecret)
                            .put("startUrl", "https://view.awsapps.com/start")));
            DeviceAuth.DeviceCode code = DeviceAuth.parseDeviceCode(response.body);
            if (!response.ok() || !code.isValid()) {
                throw new Exception(OAuthClient.readError(response.body,
                        "AWS could not start sign-in (HTTP " + response.status + ")."));
            }
            session.device = code;
            return code;
        }

        @Override
        public DeviceAuth.Poll pollDevice(Context context, Session session) throws Exception {
            ProviderHttp.Response response = ProviderHttp.send(null, "kiro_poll",
                    ProviderHttp.post(OIDC + "token").json(new JSONObject()
                            .put("clientId", session.extra.optString("clientId"))
                            .put("clientSecret", session.extra.optString("clientSecret"))
                            .put("grantType", "urn:ietf:params:oauth:grant-type:device_code")
                            .put("deviceCode", session.device.deviceCode)));
            return DeviceAuth.parsePoll(response.status, response.body);
        }

        @Override
        public AuthTokens finishDevice(Context context, Session session, DeviceAuth.Tokens tokens)
                throws Exception {
            // The refresh needs the registered client, so it rides along in the id-token slot.
            JSONObject client = new JSONObject()
                    .put("clientId", session.extra.optString("clientId"))
                    .put("clientSecret", session.extra.optString("clientSecret"));
            AuthTokens stored = tokens.toAuthTokens(now(), "");
            return new AuthTokens(stored.accessToken, stored.refreshToken, client.toString(),
                    stored.expiresAtMillis, "", stored.email);
        }

        @Override
        public boolean canRefresh(AuthTokens tokens) {
            return tokens.idToken.startsWith("{")
                    && !tokens.refreshToken.equals(tokens.accessToken);
        }

        @Override
        public AuthTokens refresh(Context context, AuthTokens tokens) throws Exception {
            JSONObject client = DeviceAuth.json(tokens.idToken);
            ProviderHttp.Response response = ProviderHttp.send(context, "kiro_refresh",
                    ProviderHttp.post(OIDC + "token").json(new JSONObject()
                            .put("clientId", client.optString("clientId"))
                            .put("clientSecret", client.optString("clientSecret"))
                            .put("grantType", "refresh_token")
                            .put("refreshToken", tokens.refreshToken)));
            DeviceAuth.Tokens parsed = DeviceAuth.parseTokens(response.body);
            if (!response.ok() || parsed.accessToken.isEmpty()) {
                throw new Unauthorized("Your AWS Builder ID sign-in expired. Sign in again.");
            }
            AuthTokens refreshed = parsed.toAuthTokens(now(), "");
            return tokens.mergeRefresh(new AuthTokens(refreshed.accessToken,
                    parsed.refreshToken, "", refreshed.expiresAtMillis, "", ""));
        }

        @Override
        public UsageSnapshot fetch(Context context, AuthTokens tokens) throws Exception {
            ProviderHttp.Response response = check(ProviderHttp.send(context, "kiro_usage",
                    ProviderHttp.get("https://codewhisperer.us-east-1.amazonaws.com/"
                            + "getUsageLimits?origin=AI_EDITOR&resourceType=AGENTIC_REQUEST")
                            .bearer(tokens.accessToken)), "usage");
            return nonEmpty(ProviderUsageParsers.kiro(response.body, now()));
        }
    }

    // ================================================================ Amp

    static final class Amp extends ProviderClient {
        Amp() {
            super(UsageProvider.AMP);
        }

        @Override
        public UsageSnapshot fetch(Context context, AuthTokens tokens) throws Exception {
            ProviderHttp.Response response = check(ProviderHttp.send(context, "amp_usage",
                    ProviderHttp.post("https://ampcode.com/api/internal?userDisplayBalanceInfo")
                            .bearer(tokens.accessToken)
                            .json(new JSONObject().put("method", "userDisplayBalanceInfo")
                                    .put("params", new JSONObject()))), "usage");
            return nonEmpty(ProviderUsageParsers.amp(response.body, now()));
        }
    }

    // ================================================================ OpenRouter

    static final class OpenRouter extends ProviderClient {
        OpenRouter() {
            super(UsageProvider.OPENROUTER);
        }

        @Override
        public String loopbackAuthUrl(String redirectUri, Session session) {
            return "https://openrouter.ai/auth?" + ProviderHttp.query(
                    "callback_url", redirectUri + "?state=" + session.state,
                    "code_challenge", session.challenge,
                    "code_challenge_method", "S256");
        }

        @Override
        public AuthTokens finishLoopback(Context context, Session session,
                Map<String, String> parameters, String redirectUri) throws Exception {
            ProviderHttp.Response response = ProviderHttp.send(context, "openrouter_exchange",
                    ProviderHttp.post("https://openrouter.ai/api/v1/auth/keys").json(
                            new JSONObject()
                                    .put("code", parameters.get("code"))
                                    .put("code_verifier", session.verifier)
                                    .put("code_challenge_method", "S256")));
            String key = response.json().optString("key", "");
            if (!response.ok() || key.isEmpty()) {
                throw new Exception(OAuthClient.readError(response.body,
                        "OpenRouter sign-in failed (HTTP " + response.status + ")."));
            }
            return new AuthTokens(key, key, "", 0L, API_KEY_ACCOUNT, "");
        }

        @Override
        public UsageSnapshot fetch(Context context, AuthTokens tokens) throws Exception {
            ProviderHttp.Response key = check(ProviderHttp.send(context, "openrouter_key",
                    ProviderHttp.get("https://openrouter.ai/api/v1/key")
                            .bearer(tokens.accessToken)), "usage");
            String credits = "";
            try {
                ProviderHttp.Response response = ProviderHttp.send(context, "openrouter_credits",
                        ProviderHttp.get("https://openrouter.ai/api/v1/credits")
                                .bearer(tokens.accessToken));
                if (response.ok()) credits = response.body;
            } catch (Exception ignored) {
                // Only management keys can read credits; the key limit still shows.
            }
            return nonEmpty(ProviderUsageParsers.openrouter(key.body, credits, now()));
        }
    }

    // ================================================================ Kilo

    static final class Kilo extends ProviderClient {
        static final String BASE = "https://api.kilo.ai/api/device-auth/codes";

        Kilo() {
            super(UsageProvider.KILO);
        }

        @Override
        public DeviceAuth.DeviceCode startDevice(Context context, Session session)
                throws Exception {
            ProviderHttp.Response response = ProviderHttp.send(context, "kilo_device",
                    ProviderHttp.post(BASE).json(new JSONObject()));
            DeviceAuth.DeviceCode code = DeviceAuth.parseDeviceCode(response.body);
            if (!response.ok() || code.deviceCode.isEmpty()) {
                throw new Exception(OAuthClient.readError(response.body,
                        "Kilo could not start sign-in (HTTP " + response.status + ")."));
            }
            String page = code.verificationUri.isEmpty()
                    ? "https://app.kilo.ai/device-auth?code=" + code.deviceCode
                    : code.verificationUri;
            session.device = new DeviceAuth.DeviceCode(code.deviceCode, code.userCode, page,
                    code.verificationUriComplete, 3L, code.expiresInSeconds);
            return session.device;
        }

        @Override
        public DeviceAuth.Poll pollDevice(Context context, Session session) throws Exception {
            ProviderHttp.Response response = ProviderHttp.send(null, "kilo_poll",
                    ProviderHttp.get(BASE + "/" + session.device.deviceCode));
            return DeviceAuth.parsePoll(response.status, response.body);
        }

        @Override
        public UsageSnapshot fetch(Context context, AuthTokens tokens) throws Exception {
            ProviderHttp.Response response = check(ProviderHttp.send(context, "kilo_usage",
                    ProviderHttp.get("https://api.kilo.ai/api/profile/balance")
                            .bearer(tokens.accessToken)), "balance");
            return nonEmpty(ProviderUsageParsers.kilo(response.body, now()));
        }
    }

    // ================================================================ Codebuff

    static final class Codebuff extends ProviderClient {
        static final String BASE = "https://www.codebuff.com/api/";

        Codebuff() {
            super(UsageProvider.CODEBUFF);
        }

        @Override
        public DeviceAuth.DeviceCode startDevice(Context context, Session session)
                throws Exception {
            String fingerprint = "codex-meter-" + DeviceAuth.randomToken(12);
            ProviderHttp.Response response = ProviderHttp.send(context, "codebuff_device",
                    ProviderHttp.post(BASE + "auth/cli/code").json(new JSONObject()
                            .put("fingerprintId", fingerprint)));
            JSONObject body = response.json();
            String login = body.optString("loginUrl", "");
            if (!response.ok() || login.isEmpty()) {
                throw new Exception("Codebuff could not start sign-in (HTTP " + response.status
                        + ").");
            }
            session.extra.put("fingerprintId", fingerprint)
                    .put("fingerprintHash", body.optString("fingerprintHash", ""))
                    .put("expiresAt", body.opt("expiresAt") == null ? ""
                            : String.valueOf(body.opt("expiresAt")));
            session.device = new DeviceAuth.DeviceCode(fingerprint, "", login, login, 3L, 900L);
            return session.device;
        }

        @Override
        public DeviceAuth.Poll pollDevice(Context context, Session session) throws Exception {
            ProviderHttp.Response response = ProviderHttp.send(null, "codebuff_poll",
                    ProviderHttp.get(BASE + "auth/cli/status?" + ProviderHttp.query(
                            "fingerprintId", session.extra.optString("fingerprintId"),
                            "fingerprintHash", session.extra.optString("fingerprintHash"),
                            "expiresAt", session.extra.optString("expiresAt"))));
            JSONObject user = response.json().optJSONObject("user");
            String token = user == null ? "" : user.optString("authToken", "");
            if (response.ok() && !token.isEmpty()) {
                return new DeviceAuth.Poll(DeviceAuth.PollState.DONE, new DeviceAuth.Tokens(
                        token, token, "", 0L, user.optString("email", "")), "");
            }
            // Codebuff answers 401 until the browser approves the login.
            return new DeviceAuth.Poll(DeviceAuth.PollState.PENDING, null, "");
        }

        @Override
        public AuthTokens finishDevice(Context context, Session session, DeviceAuth.Tokens tokens) {
            return new AuthTokens(tokens.accessToken, tokens.accessToken, "", 0L,
                    session.extra.optString("fingerprintId"), tokens.email);
        }

        @Override
        public UsageSnapshot fetch(Context context, AuthTokens tokens) throws Exception {
            ProviderHttp.Response response = check(ProviderHttp.send(context, "codebuff_usage",
                    ProviderHttp.post(BASE + "v1/usage").bearer(tokens.accessToken)
                            .json(new JSONObject()
                                    .put("fingerprintId", tokens.accountId)
                                    .put("authToken", tokens.accessToken))), "usage");
            return nonEmpty(ProviderUsageParsers.codebuff(response.body, now()));
        }
    }

    // ================================================================ key-only providers

    static final class KeyQuota extends ProviderClient {
        final String url;
        final long defaultSeconds;

        KeyQuota(UsageProvider provider, String url, long defaultSeconds) {
            super(provider);
            this.url = url;
            this.defaultSeconds = defaultSeconds;
        }

        @Override
        public UsageSnapshot fetch(Context context, AuthTokens tokens) throws Exception {
            ProviderHttp.Response response = check(ProviderHttp.send(context,
                    provider.id + "_usage", ProviderHttp.get(url).bearer(tokens.accessToken)),
                    "usage");
            return nonEmpty(ProviderUsageParsers.keyQuota(response.body, defaultSeconds, now()));
        }
    }

    static final class Warp extends ProviderClient {
        Warp() {
            super(UsageProvider.WARP);
        }

        @Override
        public UsageSnapshot fetch(Context context, AuthTokens tokens) throws Exception {
            JSONObject query = new JSONObject()
                    .put("operationName", "GetRequestLimitInfo")
                    .put("variables", new JSONObject())
                    .put("query", "query GetRequestLimitInfo { user { user { "
                            + "requestLimitInfo { isUnlimited nextRefreshTime requestLimit "
                            + "requestsUsedSinceLastRefresh } } } }");
            ProviderHttp.Response response = check(ProviderHttp.send(context, "warp_usage",
                    ProviderHttp.post("https://app.warp.dev/graphql/v2?op=GetRequestLimitInfo")
                            .bearer(tokens.accessToken).json(query)), "usage");
            return nonEmpty(ProviderUsageParsers.keyQuota(response.body,
                    ProviderUsageParsers.MONTH, now()));
        }
    }
}
