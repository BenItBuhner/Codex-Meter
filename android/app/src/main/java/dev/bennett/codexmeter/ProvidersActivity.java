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
 * Settings → Providers & accounts: every connected provider with its accounts underneath.
 * Drag the handle to reorder tabs, use the switch to show or hide a tab, tap a saved account to
 * make it active, tap the active one to sign out, and add another account per provider.
 * "Add a provider" connects a new one.
 */
public final class ProvidersActivity extends AppCompatActivity {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(DesignChrome.wrap(base));
    }

    private final List<UsageProvider> items = new ArrayList<>();
    private RecyclerView recycler;
    private boolean dark;
    private UsageProvider adding;
    private androidx.activity.result.ActivityResultLauncher<android.content.Intent> signIn;

    @Override
    protected void onCreate(Bundle state) {
        Ui.applySelectedTheme(this);
        super.onCreate(state);
        this.dark = Ui.isDark(this);
        signIn = registerForActivityResult(
                new androidx.activity.result.contract.ActivityResultContracts
                        .StartActivityForResult(),
                result -> {
                    if (adding != null) {
                        if (ProviderAccounts.isSignedIn(this, adding)) {
                            AccountVault.finishAdding(this, adding);
                        } else if (!ProviderPreferences.isSignInPending(this, adding)) {
                            AccountVault.cancelAdding(this, adding);
                        }
                        adding = null;
                    }
                    reload();
                });
        LinearLayout content = Ui.installPage(this, "Providers & accounts", true).content;
        // Pull-to-refresh would swallow downward drag gestures while rearranging rows.
        androidx.swiperefreshlayout.widget.SwipeRefreshLayout refresh =
                findViewById(R.id.dashboard_refresh);
        refresh.setEnabled(false);

        TextView hint = Ui.text(this,
                "Each provider is a dashboard tab. Drag to reorder, switch off to hide one. "
                        + "Tap an account to switch to it.",
                14.0f, Ui.secondaryText(dark));
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
        hintParams.setMargins(Ui.dp(this, 6), Ui.dp(this, 2), Ui.dp(this, 6), Ui.dp(this, 16));
        content.addView(hint, hintParams);

        // Connected accounts and tabs that are on; the rest live in Add account.
        collectItems();

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

        Ui.addSpacer(content, 16);
        RoundedLinearLayout addCard = Ui.cardGroup(this, dark);
        android.view.View addProvider = Ui.actionRow(this, "Add a provider",
                "Codex, Claude Code, Cursor, Copilot and more", R.drawable.ic_oui_add, v -> {
                    DiagnosticLog.info(this, "user", "add_account_opened");
                    Ui.startSecondaryActivity(this, AddAccountActivity.class);
                });
        addCard.addView(addProvider, new LinearLayout.LayoutParams(-1, -2));
        content.addView(addCard);

        TextView note = Ui.text(this,
                "With five or more on, the first three get tabs and the rest go under More.",
                12.0f, Ui.secondaryText(dark));
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-1, -2);
        noteParams.setMargins(Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 6), 0);
        content.addView(note, noteParams);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void collectItems() {
        items.clear();
        List<UsageProvider> enabled = ProviderPreferences.getEnabled(this);
        for (UsageProvider provider : ProviderPreferences.getOrder(this)) {
            if (enabled.contains(provider) || ProviderAccounts.isSignedIn(this, provider)
                    || AccountVault.hasSaved(this, provider)) {
                items.add(provider);
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private void reload() {
        if (recycler == null || recycler.getAdapter() == null) return;
        collectItems();
        recycler.getAdapter().notifyDataSetChanged();
    }

    private void addAccount(UsageProvider provider) {
        DiagnosticLog.info(this, "user", "add_account_picked", "provider", provider.id);
        if (ProviderAccounts.isSignedIn(this, provider)) {
            AccountVault.prepareToAdd(this, provider);
            adding = provider;
        }
        signIn.launch(SignInActivity.intent(this, provider));
    }

    private void onAccountTapped(UsageProvider provider, AccountVault.Account account,
            String label) {
        if (!account.active) {
            if (AccountVault.switchTo(this, provider, account.id)) {
                Toast.makeText(this, "Switched to " + label, Toast.LENGTH_SHORT).show();
            }
            reload();
            return;
        }
        int others = AccountVault.count(this, provider) - 1;
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Sign out of " + label + "?")
                .setMessage("This removes its encrypted sign-in and cached usage from this "
                        + "device." + (others > 0 ? " Your other " + provider.displayName
                        + " account takes over." : ""))
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Sign out", (d, w) -> {
                    ProviderAccounts.signOut(this, provider);
                    reload();
                })
                .show();
    }

    private void onAccountHeld(UsageProvider provider, AccountVault.Account account,
            String label) {
        if (account.active) {
            onAccountTapped(provider, account, label);
            return;
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Remove " + label + "?")
                .setMessage("Its saved sign-in and usage are removed from this device.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (d, w) -> {
                    AccountVault.remove(this, provider, account.id);
                    reload();
                })
                .show();
    }

    /** Indented account rows under a provider, then "Add account". */
    private void bindAccounts(LinearLayout container, UsageProvider provider) {
        container.removeAllViews();
        List<AccountVault.Account> accounts = AccountVault.list(this, provider);
        for (int i = 0; i < accounts.size(); i++) {
            AccountVault.Account account = accounts.get(i);
            String label = account.label(i);
            View row = accountRow(new AccountSwitcher.AvatarDrawable(this,
                            AccountSwitcher.initial(label), AccountSwitcher.colorFor(label), 32, 0),
                    label, AccountSwitcher.summary(account), account.active);
            row.setOnClickListener(v -> onAccountTapped(provider, account, label));
            row.setOnLongClickListener(v -> {
                onAccountHeld(provider, account, label);
                return true;
            });
            container.addView(row);
        }
        android.graphics.drawable.Drawable plus = getDrawable(R.drawable.ic_oui_add);
        if (plus != null) {
            plus = plus.mutate();
            plus.setTint(Ui.accent(this, dark));
        }
        View add = accountRow(plus, accounts.isEmpty() ? "Sign in"
                : "Add another account", "", false);
        ((TextView) add.findViewWithTag("title")).setTextColor(Ui.accent(this, dark));
        add.setOnClickListener(v -> addAccount(provider));
        container.addView(add);
    }

    private View accountRow(android.graphics.drawable.Drawable icon, String title,
            String summary, boolean active) {
        LinearLayout row = Ui.horizontal(this, Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 66), Ui.dp(this, 8), Ui.dp(this, 20), Ui.dp(this, 8));
        android.util.TypedValue ripple = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setBackgroundResource(ripple.resourceId);
        ImageView avatar = new ImageView(this);
        avatar.setImageDrawable(icon);
        avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int size = Ui.dp(this, 32);
        int pad = icon instanceof AccountSwitcher.AvatarDrawable ? 0 : Ui.dp(this, 4);
        avatar.setPadding(pad, pad, pad, pad);
        row.addView(avatar, new LinearLayout.LayoutParams(size, size));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView name = Ui.text(this, title, 15.0f, Ui.mainText(dark));
        name.setTag("title");
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (active) name.setTypeface(Ui.mediumTypeface(this));
        labels.addView(name);
        if (!summary.isEmpty()) {
            TextView sub = Ui.text(this, summary, 12.5f, Ui.secondaryText(dark));
            sub.setSingleLine(true);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            labels.addView(sub);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMargins(Ui.dp(this, 14), 0, Ui.dp(this, 8), 0);
        row.addView(labels, lp);
        if (active) {
            TextView mark = Ui.text(this, "\u2713", 18f, Ui.accent(this, dark));
            mark.setContentDescription("Active account");
            row.addView(mark);
        }
        return row;
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
            LinearLayout block = new LinearLayout(context);
            block.setOrientation(LinearLayout.VERTICAL);
            block.setBackgroundColor(Ui.cardColor(context, dark));
            block.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            block.setPadding(0, 0, 0, Ui.dp(context, 8));
            LinearLayout row = Ui.horizontal(context, Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(Ui.dp(context, 64));
            row.setPadding(Ui.dp(context, 22), Ui.dp(context, 8), Ui.dp(context, 16),
                    Ui.dp(context, 2));
            block.addView(row, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout accountsBox = new LinearLayout(context);
            accountsBox.setOrientation(LinearLayout.VERTICAL);
            block.addView(accountsBox, new LinearLayout.LayoutParams(-1, -2));

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

            ProviderHolder holder = new ProviderHolder(block, icon, title, summary, toggle, handle,
                    accountsBox);
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
            // The account rows underneath say who is signed in.
            holder.summary.setVisibility(View.GONE);
            bindAccounts(holder.accounts, provider);
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
            holder.accounts.setAlpha(on ? 1.0f : 0.6f);
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
        final LinearLayout accounts;

        ProviderHolder(View row, ImageView icon, TextView title, TextView summary,
                SwitchCompat toggle, ImageView handle, LinearLayout accounts) {
            super(row);
            this.accounts = accounts;
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
