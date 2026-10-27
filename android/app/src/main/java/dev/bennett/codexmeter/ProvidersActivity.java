package dev.bennett.codexmeter;

import android.content.Context;
import android.annotation.SuppressLint;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import dev.oneuiproject.oneui.widget.RoundedLinearLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * Settings → Providers: which providers get a dashboard tab and in what order. Same One UI
 * edit list as {@link DashboardReorderActivity}: drag the handle (or long-press a row) to
 * reorder, and use the switch to show or hide a provider's tab. Changes save immediately and
 * the dashboard picks them up on return.
 */
public final class ProvidersActivity extends AppCompatActivity {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(DesignChrome.wrap(base));
    }

    private final List<UsageProvider> items = new ArrayList<>();
    private RecyclerView recycler;
    private boolean dark;

    @Override
    protected void onCreate(Bundle state) {
        Ui.applySelectedTheme(this);
        super.onCreate(state);
        this.dark = Ui.isDark(this);
        LinearLayout content = Ui.installPage(this, "Dashboard tabs", true).content;
        // Pull-to-refresh would swallow downward drag gestures while rearranging rows.
        androidx.swiperefreshlayout.widget.SwipeRefreshLayout refresh =
                findViewById(R.id.dashboard_refresh);
        refresh.setEnabled(false);

        TextView hint = Ui.text(this,
                "Drag to reorder. Switch off to hide a tab.",
                14.0f, Ui.secondaryText(dark));
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
        hintParams.setMargins(Ui.dp(this, 6), Ui.dp(this, 2), Ui.dp(this, 6), Ui.dp(this, 16));
        content.addView(hint, hintParams);

        // Connected accounts and tabs that are on; the rest live in Add account.
        List<UsageProvider> enabled = ProviderPreferences.getEnabled(this);
        for (UsageProvider provider : ProviderPreferences.getOrder(this)) {
            if (enabled.contains(provider) || ProviderAccounts.isSignedIn(this, provider)) {
                items.add(provider);
            }
        }

        RoundedLinearLayout listCard = Ui.cardGroup(this, dark);
        recycler = new RecyclerView(this);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setNestedScrollingEnabled(false);
        ProviderAdapter adapter = new ProviderAdapter();
        recycler.setAdapter(adapter);
        ItemTouchHelper touchHelper = new ItemTouchHelper(new ReorderCallback());
        touchHelper.attachToRecyclerView(recycler);
        adapter.touchHelper = touchHelper;
        listCard.addView(recycler, new LinearLayout.LayoutParams(-1, -2));
        content.addView(listCard);

        TextView note = Ui.text(this,
                "With five or more on, the first three get tabs and the rest go under More.",
                12.0f, Ui.secondaryText(dark));
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-1, -2);
        noteParams.setMargins(Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 6), 0);
        content.addView(note, noteParams);
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    /** Provider mark for tabs and rows. */
    static int iconRes(UsageProvider provider) {
        switch (provider) {
            case CLAUDE_CODE:
                return R.drawable.ic_provider_claude_code;
            case CURSOR:
                return R.drawable.ic_provider_cursor;
            case COPILOT:
                return R.drawable.ic_provider_copilot;
            case KIMI:
                return R.drawable.ic_provider_kimi;
            case FACTORY:
                return R.drawable.ic_provider_factory;
            case GROK:
                return R.drawable.ic_provider_grok;
            case ANTIGRAVITY:
                return R.drawable.ic_provider_antigravity;
            case DEVIN:
                return R.drawable.ic_provider_devin;
            case WINDSURF:
                return R.drawable.ic_provider_windsurf;
            case AUGMENT:
                return R.drawable.ic_provider_augment;
            case CLINE:
                return R.drawable.ic_provider_cline;
            case OPENCODE_GO:
                return R.drawable.ic_provider_opencode_go;
            case MINIMAX:
                return R.drawable.ic_provider_minimax;
            case ZAI:
                return R.drawable.ic_provider_zai;
            case KIRO:
                return R.drawable.ic_provider_kiro;
            case AMP:
                return R.drawable.ic_provider_amp;
            case OPENROUTER:
                return R.drawable.ic_provider_openrouter;
            case KILO:
                return R.drawable.ic_provider_kilo;
            case CODEBUFF:
                return R.drawable.ic_provider_codebuff;
            case SYNTHETIC:
                return R.drawable.ic_provider_synthetic;
            case CHUTES:
                return R.drawable.ic_provider_chutes;
            case V0:
                return R.drawable.ic_provider_v0;
            case WARP:
                return R.drawable.ic_provider_warp;
            case CODEX:
            default:
                return R.drawable.ic_provider_codex;
        }
    }

    private void lockAncestorScrolling() {
        if (recycler != null && recycler.getParent() != null) {
            recycler.getParent().requestDisallowInterceptTouchEvent(true);
        }
    }

    private final class ProviderAdapter extends RecyclerView.Adapter<ProviderHolder> {
        ItemTouchHelper touchHelper;

        @Override
        public ProviderHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            AppCompatActivity context = ProvidersActivity.this;
            LinearLayout row = Ui.horizontal(context, Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(Ui.dp(context, 72));
            row.setPadding(Ui.dp(context, 22), Ui.dp(context, 12), Ui.dp(context, 16),
                    Ui.dp(context, 12));
            row.setBackgroundColor(Ui.cardColor(context, dark));
            row.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));

            ImageView icon = new ImageView(context);
            icon.setImageTintList(ColorStateList.valueOf(Ui.mainText(dark)));
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                    Ui.dp(context, 26), Ui.dp(context, 26));
            iconParams.setMargins(0, 0, Ui.dp(context, 18), 0);
            row.addView(icon, iconParams);

            LinearLayout labels = new LinearLayout(context);
            labels.setOrientation(LinearLayout.VERTICAL);
            TextView title = Ui.text(context, "", 17.0f, Ui.mainText(dark));
            labels.addView(title);
            TextView summary = Ui.text(context, "", 13.0f, Ui.secondaryText(dark));
            LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(-2, -2);
            summaryParams.setMargins(0, Ui.dp(context, 2), 0, 0);
            labels.addView(summary, summaryParams);
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1.0f));

            SwitchCompat toggle = new SwitchCompat(context);
            LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(-2, -2);
            toggleParams.setMargins(Ui.dp(context, 8), 0, Ui.dp(context, 4), 0);
            row.addView(toggle, toggleParams);

            ImageView handle = new ImageView(context);
            handle.setImageResource(R.drawable.ic_oui_reorder);
            handle.setImageTintList(ColorStateList.valueOf(Ui.secondaryText(dark)));
            handle.setContentDescription("Reorder");
            int pad = Ui.dp(context, 12);
            handle.setPadding(pad, pad, pad, pad);
            row.addView(handle, new LinearLayout.LayoutParams(Ui.dp(context, 48),
                    Ui.dp(context, 48)));

            ProviderHolder holder = new ProviderHolder(row, icon, title, summary, toggle, handle);
            bindDragHandle(holder);
            return holder;
        }

        @SuppressLint("ClickableViewAccessibility")
        private void bindDragHandle(ProviderHolder holder) {
            holder.handle.setOnTouchListener((view, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN && touchHelper != null) {
                    lockAncestorScrolling();
                    touchHelper.startDrag(holder);
                    return true;
                }
                return false;
            });
        }

        @Override
        public void onBindViewHolder(ProviderHolder holder, int position) {
            UsageProvider provider = items.get(position);
            holder.icon.setImageResource(iconRes(provider));
            holder.title.setText(provider.displayName);
            // Accounts already shows who is signed in; here only say when a tab has no account.
            boolean signedIn = ProviderAccounts.isSignedIn(ProvidersActivity.this, provider);
            holder.summary.setText(signedIn ? "" : "Not signed in");
            holder.summary.setVisibility(signedIn ? View.GONE : View.VISIBLE);
            holder.toggle.setContentDescription("Show " + provider.displayName + " tab");
            holder.toggle.setOnCheckedChangeListener(null);
            boolean on = ProviderPreferences.getEnabled(ProvidersActivity.this)
                    .contains(provider);
            holder.toggle.setChecked(on);
            applyRowState(holder, on);
            holder.toggle.setOnCheckedChangeListener((button, checked) -> {
                if (!ProviderPreferences.setEnabled(ProvidersActivity.this, provider, checked)) {
                    button.setChecked(!checked);
                    Toast.makeText(ProvidersActivity.this, "Keep at least one provider on.",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                DiagnosticLog.info(ProvidersActivity.this, "user", "provider_enabled_changed",
                        "provider", provider.id);
                applyRowState(holder, checked);
            });
        }

        private void applyRowState(ProviderHolder holder, boolean on) {
            float alpha = on ? 1.0f : 0.45f;
            holder.icon.setAlpha(alpha);
            holder.title.setAlpha(alpha);
            holder.summary.setAlpha(alpha);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private static final class ProviderHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView title;
        final TextView summary;
        final SwitchCompat toggle;
        final ImageView handle;

        ProviderHolder(View row, ImageView icon, TextView title, TextView summary,
                SwitchCompat toggle, ImageView handle) {
            super(row);
            this.icon = icon;
            this.title = title;
            this.summary = summary;
            this.toggle = toggle;
            this.handle = handle;
        }
    }

    private final class ReorderCallback extends ItemTouchHelper.Callback {
        @Override
        public int getMovementFlags(RecyclerView recyclerView, RecyclerView.ViewHolder holder) {
            return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
        }

        @Override
        public boolean isLongPressDragEnabled() {
            return true;
        }

        @Override
        public boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder from,
                RecyclerView.ViewHolder to) {
            int fromPosition = from.getBindingAdapterPosition();
            int toPosition = to.getBindingAdapterPosition();
            if (fromPosition < 0 || toPosition < 0) {
                return false;
            }
            items.add(toPosition, items.remove(fromPosition));
            recyclerView.getAdapter().notifyItemMoved(fromPosition, toPosition);
            ProviderPreferences.setOrder(ProvidersActivity.this, items);
            return true;
        }

        @Override
        public void onSelectedChanged(RecyclerView.ViewHolder holder, int actionState) {
            super.onSelectedChanged(holder, actionState);
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && holder != null) {
                lockAncestorScrolling();
                holder.itemView.setAlpha(0.85f);
                holder.itemView.setElevation(Ui.dp(holder.itemView.getContext(), 4));
            }
        }

        @Override
        public void clearView(RecyclerView recyclerView, RecyclerView.ViewHolder holder) {
            super.clearView(recyclerView, holder);
            holder.itemView.setAlpha(1.0f);
            holder.itemView.setElevation(0.0f);
            ProviderPreferences.setOrder(ProvidersActivity.this, items);
            DiagnosticLog.info(ProvidersActivity.this, "user", "provider_order_changed");
        }

        @Override
        public void onSwiped(RecyclerView.ViewHolder holder, int direction) {
        }
    }
}
