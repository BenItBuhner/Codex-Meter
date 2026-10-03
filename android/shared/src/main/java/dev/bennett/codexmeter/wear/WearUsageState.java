package dev.bennett.codexmeter.wear;

import dev.bennett.codexmeter.UsageSnapshot;
import org.json.JSONException;
import org.json.JSONObject;

public final class WearUsageState {
    public final java.util.List<WearAccount> accounts;
    public final String selectedAccountId;
    public final boolean signedIn;
    public final String sourceNode;
    public final UsageSnapshot snapshot;
    public final long updatedAtMillis;

    public WearUsageState(UsageSnapshot snapshot, long updatedAtMillis, String sourceNode) {
        this(snapshot, updatedAtMillis, sourceNode, snapshot != null);
    }

    public WearUsageState(UsageSnapshot snapshot, long updatedAtMillis, String sourceNode,
            boolean signedIn) {
        this(snapshot, updatedAtMillis, sourceNode, signedIn, java.util.Collections.emptyList(), "");
    }

    public WearUsageState(UsageSnapshot snapshot, long updatedAtMillis, String sourceNode,
            boolean signedIn, java.util.List<WearAccount> accounts, String selectedAccountId) {
        this.accounts = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(accounts));
        this.selectedAccountId = selectedAccountId == null ? "" : selectedAccountId;
        this.snapshot = snapshot;
        this.updatedAtMillis = Math.max(0L, updatedAtMillis);
        this.sourceNode = WearSettingsState.SOURCE_WEAR.equals(sourceNode)
                ? WearSettingsState.SOURCE_WEAR : WearSettingsState.SOURCE_PHONE;
        this.signedIn = signedIn;
    }

    public WearAccount selectedAccount(String localId) {
        String id = localId == null || localId.isEmpty() ? selectedAccountId : localId;
        for (WearAccount account : accounts) if (account.accountId.equals(id)) return account;
        for (WearAccount account : accounts) if (account.accountId.equals(selectedAccountId)) return account;
        return accounts.isEmpty() ? null : accounts.get(0);
    }

    public String shortLabel(String localId, int maxLength) {
        WearAccount account = selectedAccount(localId);
        if (account == null || maxLength < 1) return "";
        if (account.displayName.length() <= maxLength) return account.displayName;
        String suffix = "·" + (accounts.indexOf(account) + 1);
        int prefixLength = Math.max(0, maxLength - suffix.length());
        return account.displayName.substring(0, prefixLength) + suffix;
    }

    public UsageSnapshot selectedSnapshot(String localId) {
        WearAccount account = selectedAccount(localId);
        return account == null ? snapshot : account.snapshot;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        if (snapshot != null) {
            json.put("usage", snapshot.toJson());
        }
        json.put("signed_in", signedIn);
        json.put("updated_at_millis", updatedAtMillis);
        json.put("source_node", sourceNode);
        org.json.JSONArray list = new org.json.JSONArray();
        for (WearAccount account : accounts) list.put(account.toJson());
        json.put("accounts", list);
        json.put("selected_account_id", selectedAccountId);
        return json;
    }

    public static WearUsageState fromJson(JSONObject json) {
        if (json == null) return null;
        UsageSnapshot snapshot = UsageSnapshot.fromJson(json.optJSONObject("usage"));
        java.util.List<WearAccount> accounts = new java.util.ArrayList<>();
        org.json.JSONArray list = json.optJSONArray("accounts");
        if (list != null) {
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (int i = 0; i < Math.min(list.length(), 100); i++) {
                WearAccount account = WearAccount.fromJson(list.optJSONObject(i));
                if (account != null && ids.add(account.accountId)) accounts.add(account);
            }
        }
        return new WearUsageState(
                snapshot,
                json.optLong("updated_at_millis", 0L),
                json.optString("source_node", WearSettingsState.SOURCE_PHONE),
                json.has("signed_in") ? json.optBoolean("signed_in", false) : snapshot != null, accounts, json.optString("selected_account_id", ""));
    }
}
