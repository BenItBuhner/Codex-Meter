package dev.bennett.codexmeter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class OAuthService extends Service {
    public static final String ACTION_START = "dev.bennett.codexmeter.oauth.START";
    public static final String ACTION_CANCEL = "dev.bennett.codexmeter.oauth.CANCEL";
    public static final String ACTION_CANCEL_SILENT = "dev.bennett.codexmeter.oauth.CANCEL_SILENT";
    /** {@link UsageProvider#id} to sign in to; missing means Codex. */
    public static final String EXTRA_PROVIDER = "provider";
    /** Broadcast while a device-code sign-in waits for the user to enter its code. */
    public static final String ACTION_DEVICE_CODE = "dev.bennett.codexmeter.oauth.DEVICE_CODE";
    public static final String EXTRA_USER_CODE = "user_code";
    public static final String EXTRA_VERIFY_URL = "verify_url";
    /** The code being shown, so a reopened sign-in screen can show it again. */
    private volatile DeviceAuth.DeviceCode deviceCode;
    private static final String CHANNEL_ID = "oauth_sign_in";
    private static final int NOTIFICATION_ID = 7301;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ServerSocket serverSocket;
    private volatile boolean cancelled;
    private volatile UsageProvider provider = UsageProvider.CODEX;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        DiagnosticLog.info(this, "auth", "oauth_service_command",
                "action", action == null ? "" : action);
        if (ACTION_CANCEL.equals(action) || ACTION_CANCEL_SILENT.equals(action)) {
            cancelFlow("Sign-in cancelled.", ACTION_CANCEL.equals(action));
            return START_NOT_STICKY;
        }
        UsageProvider requested = UsageProvider.fromId(
                intent == null ? null : intent.getStringExtra(EXTRA_PROVIDER));
        if (SecureTokenStore.isSignedIn(this, requested)) {
            if (!running.get()) {
                AppPreferences.setOAuthPending(this, false, "");
                broadcastResult(true, "Already signed in.");
                finishService();
            }
            return START_NOT_STICKY;
        }
        if (running.get() && requested != provider) {
            broadcastResult(false, "A " + provider.accountName + " sign-in is still open. "
                    + "Finish it or cancel it from the notification first.");
            return START_NOT_STICKY;
        }
        if (running.compareAndSet(false, true)) {
            cancelled = false;
            provider = requested;
            ProviderPreferences.setPendingSignIn(this, requested);
            startForegroundCompat(buildNotification("Preparing secure sign-in…", null));
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    runFlow();
                }
            });
        } else {
            DeviceAuth.DeviceCode code = deviceCode;
            if (code != null) {
                broadcastDeviceCode(code);
            } else {
                String url = AppPreferences.getOAuthUrl(this);
                if (!url.isEmpty()) broadcastReady(url);
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        closeServer();
        executor.shutdownNow();
        super.onDestroy();
    }

    private void runFlow() {
        Socket browser = null;
        boolean credentialsCommitted = false;
        long started = android.os.SystemClock.elapsedRealtime();
        final UsageProvider flowProvider = provider;
        if (flowProvider == UsageProvider.CURSOR) {
            runCursorFlow(started);
            return;
        }
        ProviderClient client = ProviderClients.get(flowProvider);
        if (client != null) {
            runClientFlow(client, started);
            return;
        }
        final boolean claude = flowProvider == UsageProvider.CLAUDE_CODE;
        final String callbackPath = claude ? ClaudeCodeApi.CALLBACK_PATH : "/auth/callback";
        DiagnosticLog.info(this, "auth", "oauth_flow_started", "provider", flowProvider.id);
        try {
            Pkce pkce = Pkce.generate();
            int port = claude ? bindEphemeralServer() : bindServer();
            String redirectUri = "http://localhost:" + port + callbackPath;
            String authUrl = claude
                    ? ClaudeCodeApi.authorizeUrl(redirectUri, pkce.challenge, pkce.state)
                    : buildAuthorizeUrl(redirectUri, pkce);
            AppPreferences.setOAuthPending(this, true, authUrl);
            updateNotification("Complete sign-in in your browser", authUrl);
            broadcastReady(authUrl);

            while (!cancelled) {
                try {
                    browser = serverSocket.accept();
                } catch (SocketTimeoutException timeout) {
                    throw new Exception("Sign-in timed out. Start again from the app.");
                }
                browser.setSoTimeout(30000);
                Callback callback = readCallback(browser);
                if (!callbackPath.equals(callback.path)) {
                    writeBrowser(browser, 404, "Not found", false);
                    closeQuietly(browser);
                    browser = null;
                    continue;
                }
                if (!secureEquals(pkce.state, callback.parameters.get("state"))) {
                    DiagnosticLog.warn(this, "auth", "oauth_callback_state_mismatch");
                    writeBrowser(browser, 400,
                            "The sign-in state did not match. Return to Codex Meter and try again.", false);
                    closeQuietly(browser);
                    browser = null;
                    continue;
                }
                String error = callback.parameters.get("error");
                if (error != null && !error.isEmpty()) {
                    String description = callback.parameters.get("error_description");
                    String message = description == null || description.isEmpty() ? error : description;
                    writeBrowser(browser, 400, message, false);
                    closeQuietly(browser);
                    browser = null;
                    throw new Exception("Sign-in failed: " + message);
                }
                String code = callback.parameters.get("code");
                if (code == null || code.isEmpty()) {
                    writeBrowser(browser, 400,
                            "The authorization response did not include a code.", false);
                    closeQuietly(browser);
                    browser = null;
                    throw new Exception("Sign-in failed because no authorization code was returned.");
                }

                updateNotification("Securing your " + flowProvider.accountName + " session…",
                        null);
                AuthTokens tokens = claude
                        ? ClaudeUsageApi.exchangeCode(this, code, redirectUri, pkce.verifier,
                                pkce.state)
                        : OAuthClient.exchangeCode(this, code, redirectUri, pkce.verifier);
                SecureTokenStore.save(this, flowProvider, tokens);
                credentialsCommitted = true;
                AppPreferences.setOAuthPending(this, false, "");

                // The browser callback is complete as soon as credentials are safely stored.
                // Usage retrieval and JobScheduler setup must never turn a successful OAuth
                // exchange into a misleading browser error page.
                try {
                    writeBrowser(browser, 200,
                            "Your " + flowProvider.accountName
                                    + " account is connected. Returning to Codex Meter…", true);
                } catch (Exception ignored) {
                    // The user may have closed the browser after authorization. Authentication
                    // remains valid and the application still receives the result broadcast.
                }
                closeQuietly(browser);
                browser = null;
                broadcastResult(true, "Signed in successfully.");

                updateNotification("Loading " + flowProvider.displayName + " usage…", null);
                if (claude) {
                    performClaudePostAuthenticationSetup();
                } else {
                    performPostAuthenticationSetup();
                }
                DiagnosticLog.info(this, "auth", "oauth_flow_succeeded",
                        "duration_ms", android.os.SystemClock.elapsedRealtime() - started);
                finishService();
                return;
            }
        } catch (Exception exception) {
            DiagnosticLog.error(this, "auth", "oauth_flow_failed", exception,
                    "credentials_committed", credentialsCommitted,
                    "duration_ms", android.os.SystemClock.elapsedRealtime() - started);
            if (credentialsCommitted) {
                // A post-commit failure is not an authentication failure. Preserve the session,
                // show a valid success state, and let manual refresh recover later.
                AppPreferences.setOAuthPending(this, false, "");
                ProviderPreferences.setLastError(this, flowProvider, cleanMessage(exception));
                broadcastResult(true, "Signed in. Usage can be refreshed from the app.");
                safeWidgetUpdate();
                finishService();
                return;
            }
            if (browser != null) {
                try {
                    writeBrowser(browser, 500, cleanMessage(exception), false);
                } catch (Exception ignored) {
                    // The callback connection may already be gone.
                }
            }
            if (!cancelled) {
                String message = cleanMessage(exception);
                AppPreferences.setOAuthPending(this, false, "");
                broadcastResult(false, message);
            }
            finishService();
        } finally {
            closeQuietly(browser);
            closeServer();
        }
    }

    /**
     * Cursor's login page needs no callback: the browser approves a PKCE challenge and the app
     * polls for the tokens, so no local server is opened.
     */
    private void runCursorFlow(long started) {
        final UsageProvider flowProvider = UsageProvider.CURSOR;
        boolean credentialsCommitted = false;
        try {
            CursorApi.LoginRequest login = CursorApi.newLoginRequest();
            String authUrl = login.loginUrl();
            AppPreferences.setOAuthPending(this, true, authUrl);
            updateNotification("Complete sign-in in your browser", authUrl);
            broadcastReady(authUrl);
            AuthTokens tokens = CursorUsageApi.pollForTokens(this, login, () -> cancelled);
            updateNotification("Securing your Cursor session…", null);
            SecureTokenStore.save(this, flowProvider, tokens);
            credentialsCommitted = true;
            AppPreferences.setOAuthPending(this, false, "");
            broadcastResult(true, "Signed in successfully.");
            updateNotification("Loading Cursor usage…", null);
            try {
                CursorUsageApi.refreshAndCache(this);
            } catch (Exception refreshError) {
                // refreshAndCache already recorded the error on the Cursor tab.
            }
            RefreshScheduler.schedulePeriodic(this);
            safeWidgetUpdate();
            broadcastUsageUpdated();
            DiagnosticLog.info(this, "auth", "oauth_flow_succeeded", "provider", flowProvider.id,
                    "duration_ms", android.os.SystemClock.elapsedRealtime() - started);
        } catch (Exception exception) {
            DiagnosticLog.error(this, "auth", "oauth_flow_failed", exception,
                    "provider", flowProvider.id,
                    "credentials_committed", credentialsCommitted,
                    "duration_ms", android.os.SystemClock.elapsedRealtime() - started);
            AppPreferences.setOAuthPending(this, false, "");
            if (credentialsCommitted) {
                ProviderPreferences.setLastError(this, flowProvider, cleanMessage(exception));
                broadcastResult(true, "Signed in. Usage can be refreshed from the app.");
                safeWidgetUpdate();
            } else if (!cancelled) {
                broadcastResult(false, cleanMessage(exception));
            }
        }
        finishService();
    }

    /**
     * Sign-in for the providers in {@link ProviderClients}: either a device code the user types
     * on the provider's page while the app polls, or a browser sign-in that returns to a local
     * port. Paste and API-key sign-ins finish in {@link SignInActivity} without this service.
     */
    private void runClientFlow(ProviderClient client, long started) {
        final UsageProvider flowProvider = client.provider;
        boolean credentialsCommitted = false;
        Socket browser = null;
        DiagnosticLog.info(this, "auth", "oauth_flow_started", "provider", flowProvider.id);
        try {
            ProviderClient.Session session = new ProviderClient.Session();
            AuthTokens tokens;
            if (ProviderCatalog.get(flowProvider).signIn == ProviderCatalog.SignIn.LOOPBACK) {
                int port = client.loopbackPort() > 0 ? bindPort(client.loopbackPort())
                        : bindEphemeralServer();
                String redirectUri = "http://127.0.0.1:" + port + client.loopbackPath();
                String authUrl = client.loopbackAuthUrl(redirectUri, session);
                AppPreferences.setOAuthPending(this, true, authUrl);
                updateNotification("Complete sign-in in your browser", authUrl);
                broadcastReady(authUrl);
                tokens = null;
                while (!cancelled && tokens == null) {
                    try {
                        browser = serverSocket.accept();
                    } catch (SocketTimeoutException timeout) {
                        throw new Exception("Sign-in timed out. Start again from the app.");
                    }
                    browser.setSoTimeout(30000);
                    Callback callback = readCallback(browser);
                    if (!client.loopbackPath().equals(callback.path)) {
                        writeBrowser(browser, 404, "Not found", false);
                        closeQuietly(browser);
                        browser = null;
                        continue;
                    }
                    String returnedState = callback.parameters.get("state");
                    if (returnedState != null && !secureEquals(session.state, returnedState)) {
                        writeBrowser(browser, 400, "The sign-in state did not match. Return "
                                + "to Codex Meter and try again.", false);
                        closeQuietly(browser);
                        browser = null;
                        continue;
                    }
                    String error = callback.parameters.get("error");
                    if (error != null && !error.isEmpty()) {
                        writeBrowser(browser, 400, error, false);
                        closeQuietly(browser);
                        browser = null;
                        throw new Exception("Sign-in failed: " + error);
                    }
                    if (callback.parameters.get("code") == null) {
                        writeBrowser(browser, 400,
                                "The sign-in response did not include a code.", false);
                        closeQuietly(browser);
                        browser = null;
                        throw new Exception("Sign-in failed because no code was returned.");
                    }
                    updateNotification("Securing your " + flowProvider.displayName
                            + " session…", null);
                    tokens = client.finishLoopback(this, session, callback.parameters,
                            redirectUri);
                }
                if (tokens == null) throw new Exception("Sign-in cancelled.");
            } else {
                DeviceAuth.DeviceCode code = client.startDevice(this, session);
                deviceCode = code;
                AppPreferences.setOAuthPending(this, true, code.openUrl());
                updateNotification(code.userCode.isEmpty()
                        ? "Approve the sign-in in your browser"
                        : "Enter code " + code.userCode + " on " + flowProvider.vendorName,
                        code.openUrl());
                broadcastDeviceCode(code);
                tokens = pollDevice(client, session, code);
            }
            deviceCode = null;
            SecureTokenStore.save(this, flowProvider, tokens);
            credentialsCommitted = true;
            if (!tokens.email.isEmpty()) {
                ProviderPreferences.setEmail(this, flowProvider, tokens.email);
            }
            ProviderPreferences.setEnabled(this, flowProvider, true);
            AppPreferences.setOAuthPending(this, false, "");
            if (browser != null) {
                try {
                    writeBrowser(browser, 200, "Your " + flowProvider.displayName
                            + " account is connected. Returning to Codex Meter…", true);
                } catch (Exception ignored) {
                    // The browser may already be closed; the sign-in is saved either way.
                }
            }
            broadcastResult(true, flowProvider.displayName + " connected.");
            updateNotification("Loading " + flowProvider.displayName + " usage…", null);
            try {
                ProviderUsageApi.refreshAndCache(this, flowProvider);
            } catch (Exception refreshError) {
                // refreshAndCache already recorded the error on the provider's tab.
            }
            RefreshScheduler.schedulePeriodic(this);
            safeWidgetUpdate();
            broadcastUsageUpdated();
            DiagnosticLog.info(this, "auth", "oauth_flow_succeeded", "provider", flowProvider.id,
                    "duration_ms", android.os.SystemClock.elapsedRealtime() - started);
        } catch (Exception exception) {
            deviceCode = null;
            DiagnosticLog.error(this, "auth", "oauth_flow_failed", exception,
                    "provider", flowProvider.id,
                    "credentials_committed", credentialsCommitted,
                    "duration_ms", android.os.SystemClock.elapsedRealtime() - started);
            AppPreferences.setOAuthPending(this, false, "");
            if (browser != null && !credentialsCommitted) {
                try {
                    writeBrowser(browser, 500, cleanMessage(exception), false);
                } catch (Exception ignored) {
                    // The callback connection may already be gone.
                }
            }
            if (credentialsCommitted) {
                ProviderPreferences.setLastError(this, flowProvider, cleanMessage(exception));
                broadcastResult(true, "Connected. Usage can be refreshed from the app.");
                safeWidgetUpdate();
            } else if (!cancelled) {
                broadcastResult(false, cleanMessage(exception));
            }
        } finally {
            closeQuietly(browser);
            closeServer();
        }
        finishService();
    }

    /** Polls a device code until the user approves it, declines it, or it expires. */
    private AuthTokens pollDevice(ProviderClient client, ProviderClient.Session session,
            DeviceAuth.DeviceCode code) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime()
                + Math.min(code.expiresInSeconds, 30L * 60L) * 1000L;
        long interval = code.intervalSeconds;
        int failures = 0;
        while (!cancelled) {
            Thread.sleep(interval * 1000L);
            if (cancelled) break;
            if (android.os.SystemClock.elapsedRealtime() > deadline) {
                throw new Exception("The code expired before it was entered. Start again.");
            }
            DeviceAuth.Poll poll;
            try {
                poll = client.pollDevice(this, session);
                failures = 0;
            } catch (java.io.IOException network) {
                if (++failures >= 5) {
                    throw new Exception("Sign-in could not be confirmed. Check your connection "
                            + "and try again.");
                }
                continue;
            }
            switch (poll.state) {
                case DONE:
                    updateNotification("Securing your " + client.provider.displayName
                            + " session…", null);
                    return client.finishDevice(this, session, poll.tokens);
                case SLOW_DOWN:
                    interval = Math.min(interval + 5L, 30L);
                    break;
                case PENDING:
                    break;
                default:
                    throw new Exception(poll.message.isEmpty() ? "Sign-in failed." : poll.message);
            }
        }
        throw new Exception("Sign-in cancelled.");
    }

    private int bindPort(int port) throws Exception {
        ServerSocket candidate = new ServerSocket();
        candidate.setReuseAddress(true);
        candidate.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
        candidate.setSoTimeout(5 * 60 * 1000);
        serverSocket = candidate;
        return port;
    }

    private void broadcastDeviceCode(DeviceAuth.DeviceCode code) {
        Intent intent = new Intent(ACTION_DEVICE_CODE)
                .setPackage(getPackageName())
                .putExtra(EXTRA_PROVIDER, provider.id)
                .putExtra(EXTRA_USER_CODE, code.userCode)
                .putExtra(EXTRA_VERIFY_URL, code.openUrl());
        sendBroadcast(intent, AppConstants.INTERNAL_PERMISSION);
    }

    private void performPostAuthenticationSetup() {
        try {
            UsageSnapshot snapshot = UsageApi.refreshAndCache(this);
            RefreshScheduler.scheduleAtNextReset(this, snapshot);
        } catch (Exception refreshError) {
            AppPreferences.setLastError(this, cleanMessage(refreshError));
        }
        RefreshScheduler.schedulePeriodic(this);
        safeWidgetUpdate();
        broadcastUsageUpdated();
    }

    private void performClaudePostAuthenticationSetup() {
        try {
            ClaudeUsageApi.refreshAndCache(this);
        } catch (Exception refreshError) {
            // refreshAndCache already recorded the error on the Claude Code tab.
        }
        RefreshScheduler.schedulePeriodic(this);
        safeWidgetUpdate();
        broadcastUsageUpdated();
    }

    /** Claude Code's client accepts any loopback port, so let the OS pick a free one. */
    private int bindEphemeralServer() throws Exception {
        ServerSocket candidate = new ServerSocket();
        candidate.setReuseAddress(true);
        candidate.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        candidate.setSoTimeout(5 * 60 * 1000);
        serverSocket = candidate;
        return candidate.getLocalPort();
    }

    private int bindServer() throws Exception {
        Exception last = null;
        for (int port : AppConstants.OAUTH_PORTS) {
            try {
                ServerSocket candidate = new ServerSocket();
                candidate.setReuseAddress(true);
                candidate.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
                candidate.setSoTimeout(5 * 60 * 1000);
                serverSocket = candidate;
                return port;
            } catch (Exception exception) {
                last = exception;
            }
        }
        throw new Exception("Could not open the local OAuth callback port (1455 or 1457).", last);
    }

    private static String buildAuthorizeUrl(String redirectUri, Pkce pkce) throws Exception {
        Map<String, String> params = new java.util.LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id", AppConstants.OAUTH_CLIENT_ID);
        params.put("redirect_uri", redirectUri);
        params.put("scope", AppConstants.OAUTH_SCOPE);
        params.put("code_challenge", pkce.challenge);
        params.put("code_challenge_method", "S256");
        params.put("id_token_add_organizations", "true");
        params.put("codex_cli_simplified_flow", "true");
        params.put("state", pkce.state);
        params.put("originator", AppConstants.ORIGINATOR);
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (query.length() > 0) query.append('&');
            query.append(URLEncoder.encode(entry.getKey(), "UTF-8"));
            query.append('=');
            query.append(URLEncoder.encode(entry.getValue(), "UTF-8"));
        }
        return AppConstants.AUTHORIZE_URL + "?" + query;
    }

    private static Callback readCallback(Socket socket) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                socket.getInputStream(), StandardCharsets.US_ASCII));
        String requestLine = reader.readLine();
        if (requestLine == null) throw new Exception("The browser callback was empty.");
        String headerLine;
        while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
            // Consume the request headers before writing the response.
        }
        String[] parts = requestLine.split(" ");
        if (parts.length < 2 || !"GET".equals(parts[0])) {
            return new Callback("", Collections.emptyMap());
        }
        String target = parts[1];
        int queryIndex = target.indexOf('?');
        String path = queryIndex >= 0 ? target.substring(0, queryIndex) : target;
        String query = queryIndex >= 0 ? target.substring(queryIndex + 1) : "";
        Map<String, String> parameters = new HashMap<>();
        if (!query.isEmpty()) {
            for (String pair : query.split("&")) {
                int equals = pair.indexOf('=');
                String key = equals >= 0 ? pair.substring(0, equals) : pair;
                String value = equals >= 0 ? pair.substring(equals + 1) : "";
                parameters.put(URLDecoder.decode(key, "UTF-8"), URLDecoder.decode(value, "UTF-8"));
            }
        }
        return new Callback(path, parameters);
    }

    private static void writeBrowser(Socket socket, int status, String message, boolean success) throws Exception {
        String html = OAuthBrowserPage.render(message, success, AppConstants.APP_LINK);
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        String reason = status >= 200 && status < 300 ? "OK" : "Error";
        ByteArrayOutputStream response = new ByteArrayOutputStream(body.length + 256);
        response.write(("HTTP/1.1 " + status + " " + reason + "\r\n").getBytes(StandardCharsets.US_ASCII));
        response.write("Content-Type: text/html; charset=utf-8\r\n".getBytes(StandardCharsets.US_ASCII));
        response.write(("Content-Length: " + body.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
        response.write("Cache-Control: no-store\r\n".getBytes(StandardCharsets.US_ASCII));
        response.write("Connection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        response.write(body);
        socket.getOutputStream().write(response.toByteArray());
        socket.getOutputStream().flush();
    }

    private void cancelFlow(String message, boolean broadcast) {
        cancelled = true;
        AppPreferences.setOAuthPending(this, false, "");
        closeServer();
        if (broadcast) {
            broadcastResult(false, message);
        }
        finishService();
    }

    private void finishService() {
        running.set(false);
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (RuntimeException ignored) {
            // The service may not have reached foreground state if startup was interrupted.
        }
        stopSelf();
    }

    private void closeServer() {
        ServerSocket socket = serverSocket;
        serverSocket = null;
        if (socket != null) {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private void safeWidgetUpdate() {
        try {
            WidgetRenderer.updateAll(this);
        } catch (RuntimeException exception) {
            AppPreferences.setLastError(this, "Widget update: " + cleanMessage(exception));
        }
    }

    private void broadcastReady(String authUrl) {
        Intent intent = new Intent(AppConstants.ACTION_OAUTH_READY)
                .setPackage(getPackageName())
                .putExtra(EXTRA_PROVIDER, provider.id)
                .putExtra(AppConstants.EXTRA_AUTH_URL, authUrl);
        sendBroadcast(intent, AppConstants.INTERNAL_PERMISSION);
    }

    private void broadcastResult(boolean success, String message) {
        Intent intent = new Intent(AppConstants.ACTION_OAUTH_RESULT)
                .setPackage(getPackageName())
                .putExtra(EXTRA_PROVIDER, provider.id)
                .putExtra(AppConstants.EXTRA_SUCCESS, success)
                .putExtra(AppConstants.EXTRA_MESSAGE, message);
        sendBroadcast(intent, AppConstants.INTERNAL_PERMISSION);
    }

    private void broadcastUsageUpdated() {
        sendBroadcast(new Intent(AppConstants.ACTION_USAGE_UPDATED).setPackage(getPackageName()),
                AppConstants.INTERNAL_PERMISSION);
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, getString(R.string.oauth_channel_name), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.oauth_channel_description));
        channel.setShowBadge(false);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text, String authUrl) {
        Intent openIntent;
        if (authUrl == null || authUrl.isEmpty()) {
            openIntent = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        } else {
            openIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(authUrl));
        }
        PendingIntent open = PendingIntent.getActivity(this, 7302, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent cancelIntent = new Intent(this, OAuthService.class).setAction(ACTION_CANCEL);
        PendingIntent cancel = PendingIntent.getService(this, 7303, cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_oui_notification)
                .setContentTitle("Codex Meter sign-in")
                .setContentText(text)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_oui_close), "Cancel", cancel).build())
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void updateNotification(String text, String authUrl) {
        startForegroundCompat(buildNotification(text, authUrl));
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private static boolean secureEquals(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static String cleanMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.trim().isEmpty()) return "Sign-in could not be completed.";
        return message.length() > 240 ? message.substring(0, 240) : message;
    }

    private static String cleanMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.trim().isEmpty()) return exception.getClass().getSimpleName();
        return message.length() > 200 ? message.substring(0, 200) : message;
    }

    private static final class Callback {
        final String path;
        final Map<String, String> parameters;

        Callback(String path, Map<String, String> parameters) {
            this.path = path;
            this.parameters = parameters;
        }
    }
}
