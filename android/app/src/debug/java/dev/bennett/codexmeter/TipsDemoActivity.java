package dev.bennett.codexmeter;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Debug-only entry point for emulator and screenshot verification of the tips screen. */
public final class TipsDemoActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        startActivity(new Intent(this, TipsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }
}
