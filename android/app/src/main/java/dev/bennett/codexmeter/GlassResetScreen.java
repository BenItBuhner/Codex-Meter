package dev.bennett.codexmeter;

import android.content.Context;
import android.content.Intent;
import android.widget.Toast;
import dev.bennett.codexmeter.glass.GlassModel;
import dev.bennett.codexmeter.glass.GlassModel.Row;
import dev.bennett.codexmeter.glass.GlassModel.Section;
import java.util.Collections;
import java.util.List;

/**
 * Reset credits in Liquid Glass (ResetCreditActivity): what's available, when it expires, and using
 * one. Claude Code lists its saved resets by kind (Full, Session, Weekly, Fable...) with what each
 * refills, and the person picks which one to use.
 */
final class GlassResetScreen extends GlassScreens.Screen {
    private final UsageProvider provider;
    private boolean applying;
    /** The kind of Claude Code reset picked ({@link ClaudeSavedResets.Choice#key}); null for the default. */
    private String picked;
    private int notificationId = -1;

    GlassResetScreen(GlassScreens screens, UsageProvider provider) {
        super(screens);
        this.provider = provider;
    }

    private boolean claude() {
        return provider == UsageProvider.CLAUDE_CODE;
    }

    private ResetCreditsSnapshot credits() {
        if (!claude()) {
            return AppPreferences.loadResetCredits(activity());
        }
        ClaudeSavedResets saved = ProviderPreferences.loadSavedResets(activity(), provider);
        return saved == null ? null : saved.toResetCreditsSnapshot(System.currentTimeMillis());
    }

    private boolean signedIn() {
        return SecureTokenStore.isSignedIn(activity(), provider);
    }

    @Override
    void opened(Intent intent) {
        applying = false;
        refreshIfNeeded();
        if (claude() || intent == null || !intent.getBooleanExtra(AppConstants.EXTRA_PROMPT_USE_RESET, false)) {
            return;
        }
        notificationId = intent.getIntExtra(AppConstants.EXTRA_NOTIFICATION_ID, -1);
        ResetCreditsSnapshot snapshot = AppPreferences.loadResetCredits(activity());
        if (snapshot != null && snapshot.availableCount > 0 && SecureTokenStore.isSignedIn(activity())) {
            confirmUse();
        }
    }

    @Override
    GlassModel.Page page() {
        if (claude()) {
            return claudePage();
        }
        Context c = activity();
        GlassModel.Page page = new GlassModel.Page(GlassScreens.PREFIX + "reset:" + provider.id,
                provider.displayName + " reset");
        ResetCreditsSnapshot snapshot = credits();
        int available = snapshot == null ? 0 : snapshot.availableCount;
        long now = System.currentTimeMillis();
        ClaudeSavedResets saved = claude() ? ProviderPreferences.loadSavedResets(c, provider) : null;
        List<RateLimitResetCredit> credits = snapshot == null
                ? Collections.<RateLimitResetCredit>emptyList() : snapshot.availableCreditsByExpiry(now);
        long nextExpiry = snapshot == null ? 0L : snapshot.nextExpiryMillis(now);

        Section summary = GlassScreens.section("Available credits");
        Row count = GlassScreens.row(Row.Kind.INFO, "available",
                claude() && saved != null ? saved.availableTitle(now)
                        : available <= 0 ? "No resets available"
                        : available == 1 ? "1 reset available" : available + " resets available",
                nextExpiry > 0L
                        ? "Next expires " + UsageFormat.absolute(c, nextExpiry, now) + " · "
                                + UsageFormat.relative(nextExpiry, now)
                        : available > 0 ? (claude() ? "Anthropic" : "OpenAI") + " will choose an eligible credit"
                        : "No reset credit is currently available");
        count.icon = "battery";
        count.color = "#34c759";
        summary.rows.add(count);
        page.sections.add(summary);

        Section expirations = GlassScreens.section("Credit expirations");
        for (int i = 0; i < credits.size(); i++) {
            RateLimitResetCredit credit = credits.get(i);
            String title = credit.title.trim().isEmpty() ? "Reset credit " + (i + 1) : credit.title.trim();
            if (i == 0 && credit.expiresAtMillis > 0L) {
                title += " · Next";
            }
            expirations.rows.add(GlassScreens.row(Row.Kind.INFO, "c" + i, title,
                    credit.expiresAtMillis > 0L
                            ? UsageFormat.absolute(c, credit.expiresAtMillis, now) + " · "
                                    + UsageFormat.relative(credit.expiresAtMillis, now)
                            : "Expiration unavailable"));
        }
        int missing = Math.max(0, available - credits.size());
        if (missing > 0) {
            expirations.rows.add(GlassScreens.row(Row.Kind.INFO, "more", "More credits",
                    credits.isEmpty() ? "Expiration details are not available yet"
                            : missing + " additional credit" + (missing == 1 ? "" : "s")
                                    + " without expiration details"));
        } else if (available == 0) {
            expirations.rows.add(GlassScreens.row(Row.Kind.INFO, "none", "No available credits",
                    claude() ? "No reset credit is currently available" : "Earn credits from ChatGPT Codex"));
        }
        String error = claude() ? "" : AppPreferences.getVisibleResetCreditsError(c);
        String heldBack = "";
        if (claude() && available > 0) {
            heldBack = saved == null ? "" : saved.unavailableMessage(now);
        }
        StringBuilder footer = new StringBuilder(
                ResetCreditActivity.autoUseSummary(c, claude(), nextExpiry > 0L));
        if (!heldBack.isEmpty()) {
            if (footer.length() > 0) {
                footer.append("\n\n");
            }
            footer.append(heldBack).append('.');
        }
        expirations.footer = footer.length() == 0 ? null : footer.toString();
        page.sections.add(expirations);
        if (!error.isEmpty()) {
            GlassModel.Card card = new GlassModel.Card(GlassModel.Card.Kind.ERROR);
            card.title = error;
            page.sections.add(GlassScreens.cards(card));
        }

        Section use = GlassScreens.section(null);
        Row button = GlassScreens.row(Row.Kind.BUTTON, "use",
                applying ? "Applying…" : available > 0 ? "Use 1 reset" : "No resets available", null);
        button.enabled = !applying && available > 0 && heldBack.isEmpty() && signedIn();
        button.onPress = this::confirmUse;
        use.rows.add(button);
        page.sections.add(use);
        return page;
    }

    /** A tile per kind of reset: the same colors and marks the reset cards use. */
    static String choiceIcon(ClaudeSavedResets.Choice choice) {
        if (choice.modelOnly) return "starFill";
        if (choice.clearsLabel.startsWith("5-hour and")) return "bolt";
        if (choice.clearsLabel.startsWith("weekly")) return "calendar";
        return "clock";
    }

    static String choiceColor(ClaudeSavedResets.Choice choice) {
        if (choice.modelOnly) return "#d97757";
        if (choice.clearsLabel.startsWith("5-hour and")) return "#007aff";
        if (choice.clearsLabel.startsWith("weekly")) return "#5856d6";
        return "#34c759";
    }

    /** "3 left · expires Tue, Mar 9 (in 119d 9h)" under a reset kind's name. */
    static String choiceSummary(Context c, ClaudeSavedResets.Choice choice, long now) {
        StringBuilder b = new StringBuilder(ResetCreditActivity.refillsShort(choice));
        b.append('\n').append(choice.count).append(" left");
        if (choice.nextExpiryMillis > 0L) {
            b.append(" · ").append(choice.count == 1 ? "expires " : "next expires ")
                    .append(UsageFormat.absolute(c, choice.nextExpiryMillis, now));
        }
        if (!choice.held.isEmpty()) {
            b.append('\n').append(choice.held);
        }
        return b.toString();
    }

    /**
     * Claude Code: one row per kind of saved reset, each saying what it refills, how many are left
     * and when they expire; the picked one has a check, and the button names it.
     */
    private GlassModel.Page claudePage() {
        Context c = activity();
        long now = System.currentTimeMillis();
        GlassModel.Page page = new GlassModel.Page(GlassScreens.PREFIX + "reset:" + provider.id,
                provider.displayName + " reset");
        ClaudeSavedResets saved = ProviderPreferences.loadSavedResets(c, provider);
        List<ClaudeSavedResets.Choice> choices = saved == null
                ? Collections.<ClaudeSavedResets.Choice>emptyList() : saved.choices(now);
        ClaudeSavedResets.Choice choice = ClaudeSavedResets.choice(choices, picked);
        int available = saved == null ? 0 : saved.availableCount(now);
        long nextExpiry = saved == null ? 0L : saved.nextExpiryMillis(now);
        page.intro = available <= 0 ? null
                : (available == 1 ? "You have 1 saved reset. " : "You have " + available + " saved resets. ")
                        + "Pick which kind to use: each refills different limits.";

        Section pick = GlassScreens.section(available > 0 ? "Choose a reset" : "Saved resets");
        if (choices.isEmpty()) {
            pick.rows.add(GlassScreens.row(Row.Kind.INFO, "none", "No saved resets",
                    "Anthropic gives these out from time to time. New ones show up here and on Priority."));
        }
        for (ClaudeSavedResets.Choice option : choices) {
            Row row = GlassScreens.row(Row.Kind.RADIO, "choice:" + option.key, option.countedTitle(),
                    choiceSummary(c, option, now));
            row.icon = choiceIcon(option);
            row.color = choiceColor(option);
            row.checked = choice != null && option.key.equals(choice.key);
            row.onPress = () -> {
                picked = option.key;
                changed();
            };
            pick.rows.add(row);
        }
        String auto = ResetCreditActivity.autoUseSummary(c, true, nextExpiry > 0L);
        pick.footer = auto.isEmpty() ? null : auto;
        page.sections.add(pick);

        Section use = GlassScreens.section(null);
        String label = applying ? "Applying…" : choice == null ? "No resets available"
                : "Use a " + choice.title;
        Row button = GlassScreens.row(Row.Kind.BUTTON, "use", label, null);
        button.enabled = !applying && choice != null && choice.usableNow && signedIn();
        button.onPress = () -> confirmClaude(choice);
        use.rows.add(button);
        if (choice != null && !choice.usableNow && !choice.held.isEmpty()) {
            use.footer = choice.held + ".";
        }
        page.sections.add(use);
        return page;
    }

    private void confirmClaude(ClaudeSavedResets.Choice choice) {
        if (choice == null) {
            return;
        }
        new AppDialog(activity())
                .setTitle("Use a " + choice.title + "?")
                .setMessage(choice.refills + " This can't be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Use " + choice.title, (dialog, which) -> consumeClaude(choice.grantId))
                .show();
    }

    private void consumeClaude(String grantId) {
        Context app = activity().getApplicationContext();
        applying = true;
        changed();
        screens.executor.execute(() -> {
            ClaudeSavedResets.ClaimResult result = ClaudeResetApi.use(app, grantId, "reset_screen");
            activity().runOnUiThread(() -> done(result.userMessage(), result.applied()));
        });
    }

    private void refreshIfNeeded() {
        Context app = activity().getApplicationContext();
        if (claude()) {
            if (!signedIn() || !ClaudeUsageApi.isStale(app)) {
                return;
            }
            screens.executor.execute(() -> {
                try {
                    ClaudeUsageApi.refreshAndCache(app);
                    activity().runOnUiThread(this::changed);
                } catch (Exception ignored) {
                    // The Claude tab shows refresh errors; this page keeps the cached resets.
                }
            });
            return;
        }
        ResetCreditsSnapshot snapshot = AppPreferences.loadResetCredits(app);
        long now = System.currentTimeMillis();
        long age = snapshot == null ? Long.MAX_VALUE : Math.max(0L, now - snapshot.fetchedAtMillis);
        boolean missingDetails = snapshot != null && snapshot.availableCount > 0
                && snapshot.availableCreditsByExpiry(now).size() < snapshot.availableCount;
        if (SecureTokenStore.isSignedIn(app) && (age >= 300000 || missingDetails)) {
            screens.executor.execute(() -> {
                try {
                    ResetCreditApi.refreshAndCache(app);
                    activity().runOnUiThread(this::changed);
                } catch (Exception e) {
                    AppPreferences.setResetCreditsError(app, ResetCreditActivity.safeMessage(e));
                }
            });
        }
    }

    private void confirmUse() {
        new AppDialog(activity())
                .setTitle("Use one " + provider.displayName + " reset?")
                .setMessage("The available credit expiring soonest will be used. This cannot be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Use 1 reset", (dialog, which) -> consume())
                .show();
    }

    private void consume() {
        Context c = activity();
        Context app = c.getApplicationContext();
        applying = true;
        changed();
        if (claude()) {
            ClaudeSavedResets saved = ProviderPreferences.loadSavedResets(c, provider);
            ClaudeSavedResets.Grant grant = saved == null ? null : saved.usableGrant(System.currentTimeMillis());
            if (grant == null) {
                Toast.makeText(c, "No reset credit is currently available.", Toast.LENGTH_LONG).show();
                applying = false;
                changed();
                return;
            }
            String grantId = grant.id;
            screens.executor.execute(() -> {
                ClaudeSavedResets.ClaimResult result = ClaudeResetApi.use(app, grantId, "reset_screen");
                activity().runOnUiThread(() -> done(result.userMessage(), result.applied()));
            });
            return;
        }
        screens.executor.execute(() -> {
            try {
                ResetConsumeResult result = ResetCreditApi.consumeBestAvailable(app);
                activity().runOnUiThread(() -> {
                    if (result.applied()) {
                        ResetNotificationManager.dismissResetCreditExpiryNotification(c, notificationId);
                    }
                    done(result.userMessage(), result.applied());
                });
            } catch (Exception e) {
                AppPreferences.setResetCreditsError(app, ResetCreditActivity.safeMessage(e));
                activity().runOnUiThread(() -> done(ResetCreditActivity.safeMessage(e), false));
            }
        });
    }

    private void done(String message, boolean applied) {
        Toast.makeText(activity(), message, Toast.LENGTH_LONG).show();
        applying = false;
        if (applied) {
            screens.app.pop();
        }
        changed();
    }
}
