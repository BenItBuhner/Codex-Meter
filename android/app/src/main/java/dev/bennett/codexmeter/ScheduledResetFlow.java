package dev.bennett.codexmeter;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;
import java.util.Calendar;
import java.util.List;

/**
 * The dialog sequence that arms a {@link ScheduledReset}: trigger, then threshold or date, time
 * and condition, then the irreversible-action confirmation. Shared by the dashboard tile's
 * "Schedule reset" button and the reset screen so both entry points behave identically.
 */
final class ScheduledResetFlow {
    static final int REQUEST_NOTIFICATIONS = 8604;
    private static final int[] CONDITION_PRESETS = {10, 25, 50};

    private final Activity activity;
    private final Runnable onChanged;

    private ScheduledResetFlow(Activity activity, Runnable onChanged) {
        this.activity = activity;
        this.onChanged = onChanged;
    }

    /** Opens the trigger chooser; {@code onChanged} runs after a schedule is armed. */
    static void start(Activity activity, Runnable onChanged) {
        new ScheduledResetFlow(activity, onChanged).chooseTrigger();
    }

    private void chooseTrigger() {
        UsageSnapshot usage = AppPreferences.loadSnapshot(activity);
        String longLabel = usage != null && usage.longWindowIsMonthly() ? "monthly" : "weekly";
        String[] labels = {"At a date and time", "When 5-hour reaches a threshold",
                "When " + longLabel + " reaches a threshold"};
        new androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle("Scheduled reset")
                .setItems(labels, (dialog, which) -> {
                    if (which == 0) {
                        pickDate();
                    } else {
                        pickThreshold(which == 1 ? ScheduledReset.TRIGGER_FIVE_HOUR
                                : ScheduledReset.TRIGGER_WEEKLY);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void pickThreshold(String trigger) {
        UsageSnapshot usage = AppPreferences.loadSnapshot(activity);
        ScheduledReset probe = ScheduledReset.atThreshold(trigger, 0, 0L);
        UsageWindow window = probe.targetWindow(usage);
        String title = "When " + probe.windowLabel(usage) + " reaches"
                + (window == null ? "" : " (now " + window.remainingPercent() + "% remaining)");
        String[] labels = new String[ScheduledReset.THRESHOLD_PRESETS.length + 1];
        for (int index = 0; index < ScheduledReset.THRESHOLD_PRESETS.length; index++) {
            labels[index] = ScheduledReset.THRESHOLD_PRESETS[index] + "% remaining";
        }
        labels[labels.length - 1] = "Custom…";
        new androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle(title)
                .setItems(labels, (dialog, which) -> {
                    if (which < ScheduledReset.THRESHOLD_PRESETS.length) {
                        confirmSchedule(ScheduledReset.atThreshold(trigger,
                                ScheduledReset.THRESHOLD_PRESETS[which],
                                System.currentTimeMillis()));
                    } else {
                        pickCustomPercent("Custom threshold", "% remaining", value ->
                                confirmSchedule(ScheduledReset.atThreshold(trigger, value,
                                        System.currentTimeMillis())));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void pickDate() {
        Calendar calendar = Calendar.getInstance();
        new DatePickerDialog(activity, (view, year, month, day) -> {
            Calendar chosen = Calendar.getInstance();
            chosen.set(year, month, day);
            pickTime(chosen);
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void pickTime(Calendar chosen) {
        Calendar now = Calendar.getInstance();
        new TimePickerDialog(activity, (view, hour, minute) -> {
            chosen.set(Calendar.HOUR_OF_DAY, hour);
            chosen.set(Calendar.MINUTE, minute);
            chosen.set(Calendar.SECOND, 0);
            chosen.set(Calendar.MILLISECOND, 0);
            if (chosen.getTimeInMillis() <= System.currentTimeMillis()) {
                Toast.makeText(activity, "Choose a time in the future.", Toast.LENGTH_LONG).show();
                return;
            }
            pickCondition(chosen.getTimeInMillis());
        }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE),
                android.text.format.DateFormat.is24HourFormat(activity)).show();
    }

    private void pickCondition(long fireAtMillis) {
        String[] labels = new String[CONDITION_PRESETS.length + 2];
        labels[0] = "No condition";
        for (int index = 0; index < CONDITION_PRESETS.length; index++) {
            labels[index + 1] = "Only if remaining is at or below " + CONDITION_PRESETS[index] + "%";
        }
        labels[labels.length - 1] = "Custom…";
        new androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle("Condition")
                .setItems(labels, (dialog, which) -> {
                    if (which == 0) {
                        confirmSchedule(ScheduledReset.atDateTime(fireAtMillis,
                                ScheduledReset.CONDITION_OFF, System.currentTimeMillis()));
                    } else if (which <= CONDITION_PRESETS.length) {
                        confirmSchedule(ScheduledReset.atDateTime(fireAtMillis,
                                CONDITION_PRESETS[which - 1], System.currentTimeMillis()));
                    } else {
                        pickCustomPercent("Only if remaining is at or below", "%", value ->
                                confirmSchedule(ScheduledReset.atDateTime(fireAtMillis, value,
                                        System.currentTimeMillis())));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private interface PercentListener {
        void onPercent(int value);
    }

    private void pickCustomPercent(String title, String hint, PercentListener listener) {
        EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint(hint);
        input.setSingleLine(true);
        int pad = Ui.dp(activity, 24.0f);
        LinearLayout wrapper = new LinearLayout(activity);
        wrapper.setPadding(pad, Ui.dp(activity, 8.0f), pad, 0);
        wrapper.addView(input, new LinearLayout.LayoutParams(-1, -2));
        new androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(wrapper)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Next", (dialog, which) -> {
                    int value;
                    try {
                        value = Integer.parseInt(input.getText().toString().trim());
                    } catch (NumberFormatException exception) {
                        value = -1;
                    }
                    if (value < 0 || value > 99) {
                        Toast.makeText(activity, "Enter a percentage from 0 to 99.",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    listener.onPercent(value);
                })
                .show();
    }

    private void confirmSchedule(ScheduledReset schedule) {
        long now = System.currentTimeMillis();
        UsageSnapshot usage = AppPreferences.loadSnapshot(activity);
        ResetCreditsSnapshot credits = AppPreferences.loadResetCredits(activity);
        StringBuilder message = new StringBuilder(schedule.confirmationText(usage,
                ScheduledResetManager.dateTimeText(activity, schedule.fireAtMillis, now, true)));
        message.append(" The credit expiring soonest is used, and this cannot be undone once it runs.");
        ScheduledReset existing = ScheduledResetManager.load(activity);
        if (existing != null) {
            message.append("\n\nThis replaces ").append(existing.armedLabel(usage,
                    ScheduledResetManager.dateTimeText(activity, existing.fireAtMillis, now,
                            false)))
                    .append('.');
        }
        UsageWindow window = schedule.targetWindow(usage);
        if (window != null && window.remainingPercent() <= schedule.thresholdPercent) {
            message.append("\n\nYour ").append(schedule.windowLabel(usage))
                    .append(" limit is already at ").append(window.remainingPercent())
                    .append("% remaining, so this runs at the next refresh.");
        }
        if (credits != null && schedule.creditsExpireBefore(expiries(credits, now))) {
            message.append("\n\nAll of your reset credits expire before then, so this schedule "
                    + "would find none to use.");
        }
        new AlertDialog.Builder(activity)
                .setTitle("Schedule a Codex reset?")
                .setMessage(message.toString())
                .setNegativeButton("Cancel", (DialogInterface.OnClickListener) null)
                .setPositiveButton("Schedule", (dialog, which) -> {
                    ScheduledResetManager.arm(activity, schedule);
                    DiagnosticLog.info(activity, "user", "scheduled_reset_armed",
                            "trigger", schedule.trigger,
                            "screen", activity.getClass().getSimpleName());
                    Toast.makeText(activity, "Scheduled reset armed.", Toast.LENGTH_SHORT).show();
                    if (onChanged != null) {
                        onChanged.run();
                    }
                    requestNotificationPermissionIfNeeded();
                })
                .show();
    }

    private static long[] expiries(ResetCreditsSnapshot credits, long now) {
        List<RateLimitResetCredit> available = credits.availableCreditsByExpiry(now);
        long[] values = new long[available.size()];
        for (int index = 0; index < values.length; index++) {
            values[index] = available.get(index).expiresAtMillis;
        }
        return values;
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && activity.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},
                    REQUEST_NOTIFICATIONS);
        }
    }
}
