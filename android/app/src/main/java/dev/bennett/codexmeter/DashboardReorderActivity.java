package dev.bennett.codexmeter;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import dev.oneuiproject.oneui.layout.ToolbarLayout;
import dev.oneuiproject.oneui.widget.CardItemView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One UI edit screen for dashboard order. Uses SESL RecyclerView + ItemTouchHelper
 * drag handles so limits (including auto-detected SPARK windows) and credits can be
 * moved above or below each other.
 */
public final class DashboardReorderActivity extends AppCompatActivity {
    private final List<ReorderItem> items = new ArrayList<>();
    private Adapter adapter;
    private ItemTouchHelper itemTouchHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Ui.applySelectedTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard_reorder);
        ToolbarLayout toolbar = findViewById(R.id.dashboard_reorder_toolbar);
        Ui.configureReachToolbar(toolbar, "Edit dashboard", true);

        boolean dark = Ui.isDark(this);
        TextView help = findViewById(R.id.dashboard_reorder_help);
        help.setTextColor(Ui.secondaryText(dark));

        loadItems();
        RecyclerView list = findViewById(R.id.dashboard_reorder_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.seslSetFillBottomEnabled(true);
        list.seslSetLastRoundedCorner(true);
        adapter = new Adapter();
        list.setAdapter(adapter);
        itemTouchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView,
                    @NonNull RecyclerView.ViewHolder viewHolder,
                    @NonNull RecyclerView.ViewHolder target) {
                int from = viewHolder.getBindingAdapterPosition();
                int to = target.getBindingAdapterPosition();
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION
                        || from == to) {
                    return false;
                }
                Collections.swap(items, from, to);
                adapter.notifyItemMoved(from, to);
                persistOrder();
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                // Reorder only.
            }

            @Override
            public boolean isLongPressDragEnabled() {
                return true;
            }
        });
        itemTouchHelper.attachToRecyclerView(list);
    }

    private void loadItems() {
        items.clear();
        UsageSnapshot snapshot = AppPreferences.loadSnapshot(this);
        boolean showResetCredits = AppPreferences.showDashboardResetCredits(this)
                && SecureTokenStore.isSignedIn(this)
                && (AppPreferences.loadResetCredits(this) != null
                || (snapshot != null && snapshot.resetCreditsAvailable >= 0));
        List<String> natural = naturalOrder(snapshot, showResetCredits);
        List<String> ordered = DashboardOrder.merge(
                AppPreferences.getDashboardItemOrder(this), natural);
        for (String id : ordered) {
            ReorderItem item = describe(id, snapshot);
            if (item != null) {
                items.add(item);
            }
        }
    }

    private List<String> naturalOrder(UsageSnapshot snapshot, boolean showResetCredits) {
        List<String> available = new ArrayList<>();
        if (snapshot == null) {
            if (showResetCredits) {
                available.add(DashboardOrder.RESET_CREDITS);
            }
            return available;
        }
        if (AppPreferences.showDashboardFiveHour(this) && snapshot.fiveHour != null) {
            available.add(DashboardOrder.FIVE_HOUR);
        }
        if (AppPreferences.showDashboardWeekly(this) && snapshot.weekly != null) {
            available.add(DashboardOrder.WEEKLY);
        }
        if (AppPreferences.showDashboardAdditionalLimits(this)
                && snapshot.additionalLimits != null) {
            for (UsageLimit limit : snapshot.additionalLimits) {
                if (limit == null) {
                    continue;
                }
                if (limit.primary != null) {
                    available.add(DashboardOrder.additionalPrimary(limit.id));
                }
                if (limit.secondary != null) {
                    available.add(DashboardOrder.additionalSecondary(limit.id));
                }
            }
        }
        if (AppPreferences.showDashboardUsageCredits(this)
                && snapshot.usageCredits != null
                && snapshot.usageCredits.isDashboardVisible()) {
            available.add(DashboardOrder.USAGE_CREDITS);
        }
        if (showResetCredits) {
            available.add(DashboardOrder.RESET_CREDITS);
        }
        return available;
    }

    private ReorderItem describe(String id, UsageSnapshot snapshot) {
        if (DashboardOrder.FIVE_HOUR.equals(id)) {
            return new ReorderItem(id, "5-hour limit", "Standard Codex window",
                    R.drawable.ic_oui_time);
        }
        if (DashboardOrder.WEEKLY.equals(id)) {
            return new ReorderItem(id, "Weekly limit", "Standard Codex window",
                    R.drawable.ic_oui_calendar_week);
        }
        if (DashboardOrder.USAGE_CREDITS.equals(id)) {
            return new ReorderItem(id, "Usage-credit balance",
                    "Hidden automatically when at or near zero",
                    R.drawable.ic_oui_battery);
        }
        if (DashboardOrder.RESET_CREDITS.equals(id)) {
            return new ReorderItem(id, "Reset credits",
                    "Earned credits that can reset usage windows",
                    R.drawable.ic_oui_refresh);
        }
        if (DashboardOrder.isAdditional(id) && snapshot != null
                && snapshot.additionalLimits != null) {
            for (UsageLimit limit : snapshot.additionalLimits) {
                if (limit == null) {
                    continue;
                }
                if (DashboardOrder.additionalPrimary(limit.id).equals(id)
                        && limit.primary != null) {
                    return new ReorderItem(id,
                            limit.displayName() + " · " + cadenceLabel(limit.primary),
                            "Auto-detected additional limit",
                            limit.primary.windowSeconds >= 86_400L
                                    ? R.drawable.ic_oui_calendar_week
                                    : R.drawable.ic_oui_time);
                }
                if (DashboardOrder.additionalSecondary(limit.id).equals(id)
                        && limit.secondary != null) {
                    return new ReorderItem(id,
                            limit.displayName() + " · " + cadenceLabel(limit.secondary),
                            "Auto-detected additional limit",
                            limit.secondary.windowSeconds >= 86_400L
                                    ? R.drawable.ic_oui_calendar_week
                                    : R.drawable.ic_oui_time);
                }
            }
        }
        return null;
    }

    private static String cadenceLabel(UsageWindow window) {
        long seconds = window.windowSeconds;
        if (seconds >= 432_000L && seconds <= 777_600L) {
            return "Weekly";
        }
        if (seconds >= 10_800L && seconds <= 28_800L) {
            long hours = Math.max(1L, Math.round(seconds / 3600.0d));
            return hours + "-hour";
        }
        if (seconds % 86_400L == 0L) {
            return (seconds / 86_400L) + "-day";
        }
        if (seconds % 3_600L == 0L) {
            return (seconds / 3_600L) + "-hour";
        }
        return "Usage";
    }

    private void persistOrder() {
        List<String> ids = new ArrayList<>(items.size());
        for (ReorderItem item : items) {
            ids.add(item.id);
        }
        AppPreferences.setDashboardItemOrder(this, ids);
    }

    private static final class ReorderItem {
        final String id;
        final String title;
        final String summary;
        final int iconRes;

        ReorderItem(String id, String title, String summary, int iconRes) {
            this.id = id;
            this.title = title;
            this.summary = summary;
            this.iconRes = iconRes;
        }
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            CardItemView row = new CardItemView(parent.getContext());
            row.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new Holder(row);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            holder.bind(items.get(position));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private final class Holder extends RecyclerView.ViewHolder {
        private final CardItemView row;

        Holder(CardItemView row) {
            super(row);
            this.row = row;
        }

        @SuppressLint("ClickableViewAccessibility")
        void bind(ReorderItem item) {
            row.setTitle(item.title);
            row.setSummary(item.summary);
            row.setIcon(getDrawable(item.iconRes));
            row.setShowTopDivider(getBindingAdapterPosition() > 0);
            ImageView handle = row.getEndImageView();
            handle.setVisibility(View.VISIBLE);
            handle.setImageResource(R.drawable.ic_oui_reorder);
            handle.setContentDescription("Drag to reorder");
            handle.setOnTouchListener((view, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                        && itemTouchHelper != null) {
                    itemTouchHelper.startDrag(Holder.this);
                }
                return false;
            });
        }
    }
}
