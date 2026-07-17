package dev.bennett.codexmeter;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.RemoteException;
import android.util.Log;
import androidx.wear.watchface.complications.data.ComplicationData;
import androidx.wear.watchface.complications.data.ComplicationText;
import androidx.wear.watchface.complications.data.ComplicationType;
import androidx.wear.watchface.complications.data.LongTextComplicationData;
import androidx.wear.watchface.complications.data.PlainComplicationText;
import androidx.wear.watchface.complications.data.RangedValueComplicationData;
import androidx.wear.watchface.complications.data.ShortTextComplicationData;
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService;
import androidx.wear.watchface.complications.datasource.ComplicationRequest;
import java.util.concurrent.TimeUnit;

abstract class CodexComplicationService extends ComplicationDataSourceService {
    private static final int REQUEST_COMPLICATION_TAP = 8720;
    private static final String TAG = "CodexComplication";

    @Override
    public final void onComplicationRequest(ComplicationRequest request,
            ComplicationRequestListener listener) {
        try {
            listener.onComplicationData(request == null ? null
                    : dataForType(request.getComplicationType(), false));
        } catch (RemoteException exception) {
            Log.w(TAG, "Could not deliver complication data", exception);
        }
    }

    @Override
    public final ComplicationData getPreviewData(ComplicationType type) {
        return dataForType(type, true);
    }

    protected abstract ComplicationData dataForType(ComplicationType type, boolean preview);

    protected ShortTextComplicationData shortText(String text, String title, String description) {
        ShortTextComplicationData.Builder builder = new ShortTextComplicationData.Builder(
                plain(text), plain(description)).setTapAction(tapAction(this));
        if (title != null && !title.isEmpty()) {
            builder.setTitle(plain(title));
        }
        return builder.build();
    }

    protected LongTextComplicationData longText(String text, String description) {
        return new LongTextComplicationData.Builder(plain(text), plain(description))
                .setTapAction(tapAction(this))
                .build();
    }

    protected RangedValueComplicationData rangedValue(float value, String text, String title,
            String description) {
        float clamped = Math.max(0f, Math.min(100f, value));
        RangedValueComplicationData.Builder builder = new RangedValueComplicationData.Builder(
                clamped, 0f, 100f, plain(description))
                .setText(plain(text))
                .setTapAction(tapAction(this));
        if (title != null && !title.isEmpty()) {
            builder.setTitle(plain(title));
        }
        return builder.build();
    }

    protected static ComplicationText plain(String text) {
        return new PlainComplicationText.Builder(text == null ? "--" : text).build();
    }

    protected static PendingIntent tapAction(Context context) {
        Intent intent = new Intent(context, WearMainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, REQUEST_COMPLICATION_TAP, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    protected UsageSnapshot snapshot(boolean preview) {
        return preview ? previewSnapshot()
                : WearPreferences.loadSnapshot(this);
    }

    private static UsageSnapshot previewSnapshot() {
        long now = System.currentTimeMillis();
        return new UsageSnapshot("preview", true, false,
                new UsageWindow(62, TimeUnit.HOURS.toSeconds(5),
                        TimeUnit.MINUTES.toSeconds(84),
                        (now + TimeUnit.MINUTES.toMillis(84)) / 1000L),
                new UsageWindow(41, TimeUnit.DAYS.toSeconds(7),
                        TimeUnit.DAYS.toSeconds(3),
                        (now + TimeUnit.DAYS.toMillis(3)) / 1000L),
                now);
    }
}
