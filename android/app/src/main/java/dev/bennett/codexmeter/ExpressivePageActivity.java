package dev.bennett.codexmeter;

import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Hosts one Material 3 Expressive page ({@link ExpressivePages}): the intent that would have opened
 * a One UI activity (usage history, reset credits, updates…) arrives in {@link #EXTRA_INTENT}.
 */
public final class ExpressivePageActivity extends AppCompatActivity {
    static final String EXTRA_INTENT = "dev.bennett.codexmeter.extra.EXPRESSIVE_INTENT";

    @Override
    protected void onCreate(Bundle state) {
        Ui.applySelectedTheme(this);
        super.onCreate(state);
        Intent page = getIntent().getParcelableExtra(EXTRA_INTENT);
        if (page == null || !ExpressivePages.show(this, page)) {
            finish();
        }
    }
}
