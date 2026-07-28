package dev.bennett.codexmeter;

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
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import dev.oneuiproject.oneui.widget.RoundedLinearLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One UI edit screen for arranging the dashboard sections. Rows are dragged with the SESL
 * RecyclerView ItemTouchHelper, either from the reorder handle or with a long-press, and each
 * row carries a visibility switch so sections can be hidden without leaving the list. Order and
 * visibility are saved immediately so the dashboard rebuilds on return.
 */
public final class DashboardReorderActivity extends AppCompatActivity {
    private final List<SectionItem> items = new ArrayList<>();
    private RecyclerView recycler;
    private boolean dark;

    private static final class SectionItem {
        final String key;
        final String title;
        final String summary;

        SectionItem(String key, String title, String summary) {
            this.key = key;
            this.title = title;
            this.summary = summary;
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        Ui.applySelectedTheme(this);
        super.onCreate(state);
        this.dark = Ui.isDark(this);
        LinearLayout content = Ui.installPage(this, "Edit dashboard", true).content;
        // Pull-to-refresh would swallow downward drag gestures while rearranging rows.
        androidx.swiperefreshlayout.widget.SwipeRefreshLayout refresh =
                findViewById(R.id.dashboard_refresh);
        refresh.setEnabled(false);

        TextView hint = Ui.text(this,
                "Drag the handles to arrange your usage cards and use the switches to hide the "
                        + "ones you don't need. Model-specific limits such as GPT-5.3-Codex-Spark "
                        + "appear here automatically once OpenAI reports them for your account.",
                14.0f, Ui.secondaryText(dark));
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
        hintParams.setMargins(Ui.dp(this, 6), Ui.dp(this, 2), Ui.dp(this, 6), Ui.dp(this, 16));
        content.addView(hint, hintParams);

        buildItems();

        RoundedLinearLayout listCard = Ui.cardGroup(this, dark);
        recycler = new RecyclerView(this);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setNestedScrollingEnabled(false);
        SectionAdapter adapter = new SectionAdapter();
        recycler.setAdapter(adapter);
        ItemTouchHelper touchHelper = new ItemTouchHelper(new ReorderCallback());
        touchHelper.attachToRecyclerView(recycler);
        adapter.touchHelper = touchHelper;
        listCard.addView(recycler, new LinearLayout.LayoutParams(-1, -2));
        content.addView(listCard);

        TextView note = Ui.text(this,
                "Changes are saved instantly. Usage-credit balance and reset credits stay hidden "
                        + "when they have nothing to show (zero or below), and 5-hour, weekly, "
                        + "and usage-history cards appear only while OpenAI reports data for them "
                        + "— no matter where each card is placed or whether its switch is on.",
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

    private void buildItems() {
        UsageSnapshot snapshot = AppPreferences.loadSnapshot(this);
        List<UsageLimit> limits = snapshot == null
                ? Collections.<UsageLimit>emptyList() : snapshot.additionalLimits;
        List<String> ordered = DashboardSections.resolveOrder(
                AppPreferences.getDashboardOrder(this), DashboardSections.defaultOrder(limits));
        for (String key : ordered) {
            if (DashboardSections.FIVE_HOUR.equals(key)) {
                items.add(new SectionItem(key, "5-hour limit", "Rolling 5-hour Codex window"));
            } else if (DashboardSections.WEEKLY.equals(key)) {
                items.add(new SectionItem(key, "Weekly limit", "Rolling 7-day Codex window"));
            } else if (DashboardSections.USAGE_CREDITS.equals(key)) {
                items.add(new SectionItem(key, "Usage-credit balance",
                        "Hidden automatically at a zero or negative balance"));
            } else if (DashboardSections.USAGE_HISTORY.equals(key)) {
                items.add(new SectionItem(key, "Usage history",
                        "Shown only when a 5-hour or weekly window is available"));
            } else if (DashboardSections.RESET_CREDITS.equals(key)) {
                items.add(new SectionItem(key, "Reset credits",
                        "Hidden automatically when no resets are available"));
            } else {
                UsageLimit match = null;
                for (UsageLimit limit : limits) {
                    if (limit != null && DashboardSections.limitKey(limit).equals(key)) {
                        match = limit;
                        break;
                    }
                }
                items.add(new SectionItem(key,
                        match == null ? "Additional limit" : match.displayName(),
                        "Model-specific limit · detected automatically"));
            }
        }
    }

    /**
     * Stops the scroll containers above the list from intercepting the vertical drag while
     * leaving the RecyclerView itself free to run the ItemTouchHelper reorder gesture.
     */
    private void lockAncestorScrolling() {
        if (recycler != null && recycler.getParent() != null) {
            recycler.getParent().requestDisallowInterceptTouchEvent(true);
        }
    }

    private void persistOrder() {
        List<String> order = new ArrayList<>();
        for (SectionItem item : items) {
            order.add(item.key);
        }
        AppPreferences.setDashboardOrder(this, order);
    }

    private boolean isSectionVisible(String key) {
        if (DashboardSections.FIVE_HOUR.equals(key)) {
            return AppPreferences.showDashboardFiveHour(this);
        }
        if (DashboardSections.WEEKLY.equals(key)) {
            return AppPreferences.showDashboardWeekly(this);
        }
        if (DashboardSections.USAGE_CREDITS.equals(key)) {
            return AppPreferences.showDashboardUsageCredits(this);
        }
        if (DashboardSections.USAGE_HISTORY.equals(key)) {
            return AppPreferences.showDashboardUsageHistory(this);
        }
        if (DashboardSections.RESET_CREDITS.equals(key)) {
            return AppPreferences.showDashboardResetCredits(this);
        }
        return !AppPreferences.isDashboardSectionHidden(this, key);
    }

    private void setSectionVisible(String key, boolean visible) {
        if (DashboardSections.FIVE_HOUR.equals(key)) {
            AppPreferences.setShowDashboardFiveHour(this, visible);
        } else if (DashboardSections.WEEKLY.equals(key)) {
            AppPreferences.setShowDashboardWeekly(this, visible);
        } else if (DashboardSections.USAGE_CREDITS.equals(key)) {
            AppPreferences.setShowDashboardUsageCredits(this, visible);
        } else if (DashboardSections.USAGE_HISTORY.equals(key)) {
            AppPreferences.setShowDashboardUsageHistory(this, visible);
        } else if (DashboardSections.RESET_CREDITS.equals(key)) {
            AppPreferences.setShowDashboardResetCredits(this, visible);
        } else {
            AppPreferences.setDashboardSectionHidden(this, key, !visible);
        }
    }

    private final class SectionAdapter extends RecyclerView.Adapter<SectionHolder> {
        ItemTouchHelper touchHelper;

        @Override
        public SectionHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout row = Ui.horizontal(DashboardReorderActivity.this,
                    Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(Ui.dp(DashboardReorderActivity.this, 72));
            row.setPadding(Ui.dp(DashboardReorderActivity.this, 22),
                    Ui.dp(DashboardReorderActivity.this, 12),
                    Ui.dp(DashboardReorderActivity.this, 16),
                    Ui.dp(DashboardReorderActivity.this, 12));
            row.setBackgroundColor(Ui.cardColor(DashboardReorderActivity.this, dark));
            row.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));

            LinearLayout labels = new LinearLayout(DashboardReorderActivity.this);
            labels.setOrientation(LinearLayout.VERTICAL);
            TextView title = Ui.text(DashboardReorderActivity.this, "", 17.0f, Ui.mainText(dark));
            labels.addView(title);
            TextView summary = Ui.text(DashboardReorderActivity.this, "", 13.0f,
                    Ui.secondaryText(dark));
            LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(-2, -2);
            summaryParams.setMargins(0, Ui.dp(DashboardReorderActivity.this, 2), 0, 0);
            labels.addView(summary, summaryParams);
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1.0f));

            SwitchCompat toggle = new SwitchCompat(DashboardReorderActivity.this);
            toggle.setContentDescription("Show on dashboard");
            LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(-2, -2);
            toggleParams.setMargins(Ui.dp(DashboardReorderActivity.this, 8), 0,
                    Ui.dp(DashboardReorderActivity.this, 4), 0);
            row.addView(toggle, toggleParams);

            ImageView handle = new ImageView(DashboardReorderActivity.this);
            handle.setImageResource(R.drawable.ic_oui_reorder);
            handle.setImageTintList(ColorStateList.valueOf(Ui.secondaryText(dark)));
            handle.setContentDescription("Reorder");
            int pad = Ui.dp(DashboardReorderActivity.this, 12);
            handle.setPadding(pad, pad, pad, pad);
            row.addView(handle, new LinearLayout.LayoutParams(
                    Ui.dp(DashboardReorderActivity.this, 48),
                    Ui.dp(DashboardReorderActivity.this, 48)));

            SectionHolder holder = new SectionHolder(row, title, summary, toggle, handle);
            bindDragHandle(holder);
            return holder;
        }

        @SuppressLint("ClickableViewAccessibility")
        private void bindDragHandle(SectionHolder holder) {
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
        public void onBindViewHolder(SectionHolder holder, int position) {
            SectionItem item = items.get(position);
            holder.title.setText(item.title);
            holder.summary.setText(item.summary);
            holder.toggle.setOnCheckedChangeListener(null);
            boolean visible = isSectionVisible(item.key);
            holder.toggle.setChecked(visible);
            applyRowVisibility(holder, visible);
            holder.toggle.setOnCheckedChangeListener((button, checked) -> {
                setSectionVisible(item.key, checked);
                applyRowVisibility(holder, checked);
            });
        }

        private void applyRowVisibility(SectionHolder holder, boolean visible) {
            float alpha = visible ? 1.0f : 0.45f;
            holder.title.setAlpha(alpha);
            holder.summary.setAlpha(alpha);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private static final class SectionHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView summary;
        final SwitchCompat toggle;
        final ImageView handle;

        SectionHolder(View row, TextView title, TextView summary, SwitchCompat toggle,
                ImageView handle) {
            super(row);
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
            if (fromPosition < toPosition) {
                for (int i = fromPosition; i < toPosition; i++) {
                    Collections.swap(items, i, i + 1);
                }
            } else {
                for (int i = fromPosition; i > toPosition; i--) {
                    Collections.swap(items, i, i - 1);
                }
            }
            recyclerView.getAdapter().notifyItemMoved(fromPosition, toPosition);
            persistOrder();
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
            persistOrder();
        }

        @Override
        public void onSwiped(RecyclerView.ViewHolder holder, int direction) {
        }
    }
}
