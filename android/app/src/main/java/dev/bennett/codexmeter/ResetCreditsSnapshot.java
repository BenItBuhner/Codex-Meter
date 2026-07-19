package dev.bennett.codexmeter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes.dex */
public final class ResetCreditsSnapshot {
    public final int availableCount;
    public final List<RateLimitResetCredit> credits;
    public final long fetchedAtMillis;

    public ResetCreditsSnapshot(int i, List<RateLimitResetCredit> list, long j) {
        ArrayList arrayList;
        this.availableCount = Math.max(0, i);
        if (list == null) {
            arrayList = new ArrayList();
        } else {
            arrayList = new ArrayList(list);
        }
        this.credits = Collections.unmodifiableList(arrayList);
        this.fetchedAtMillis = Math.max(0L, j);
    }

    public static ResetCreditsSnapshot summary(int i, long j) {
        return new ResetCreditsSnapshot(i, Collections.emptyList(), j);
    }

    public RateLimitResetCredit nextExpiringAvailable(long nowMillis) {
        List<RateLimitResetCredit> available = availableCreditsSortedByExpiry(nowMillis);
        return available.isEmpty() ? null : available.get(0);
    }

    /**
     * Available, unexpired credits ordered by soonest expiry first.
     * Credits without an expiry timestamp sort last.
     */
    public List<RateLimitResetCredit> availableCreditsSortedByExpiry(long nowMillis) {
        ArrayList<RateLimitResetCredit> available = new ArrayList<>();
        for (RateLimitResetCredit credit : credits) {
            if (credit == null || !credit.isAvailable()) continue;
            long expiry = credit.expiresAtMillis;
            if (expiry > 0L && expiry <= nowMillis) continue;
            available.add(credit);
        }
        Collections.sort(available, (left, right) -> {
            long leftExpiry = left.expiresAtMillis;
            long rightExpiry = right.expiresAtMillis;
            if (leftExpiry <= 0L && rightExpiry <= 0L) return 0;
            if (leftExpiry <= 0L) return 1;
            if (rightExpiry <= 0L) return -1;
            return Long.compare(leftExpiry, rightExpiry);
        });
        return Collections.unmodifiableList(available);
    }

    public String preferredCreditId(long j) {
        RateLimitResetCredit rateLimitResetCreditNextExpiringAvailable = nextExpiringAvailable(j);
        return rateLimitResetCreditNextExpiringAvailable == null ? "" : rateLimitResetCreditNextExpiringAvailable.id;
    }

    public long nextExpiryMillis(long j) {
        RateLimitResetCredit rateLimitResetCreditNextExpiringAvailable = nextExpiringAvailable(j);
        if (rateLimitResetCreditNextExpiringAvailable == null) {
            return 0L;
        }
        return rateLimitResetCreditNextExpiringAvailable.expiresAtMillis;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject jSONObject = new JSONObject();
        jSONObject.put("available_count", this.availableCount);
        jSONObject.put("fetched_at", this.fetchedAtMillis);
        JSONArray jSONArray = new JSONArray();
        for (RateLimitResetCredit rateLimitResetCredit : this.credits) {
            if (rateLimitResetCredit != null) {
                jSONArray.put(rateLimitResetCredit.toJson());
            }
        }
        jSONObject.put("credits", jSONArray);
        return jSONObject;
    }

    public static ResetCreditsSnapshot fromJson(JSONObject jSONObject) {
        if (jSONObject == null) {
            return null;
        }
        ArrayList arrayList = new ArrayList();
        JSONArray jSONArrayOptJSONArray = jSONObject.optJSONArray("credits");
        if (jSONArrayOptJSONArray != null) {
            for (int i = 0; i < jSONArrayOptJSONArray.length(); i++) {
                RateLimitResetCredit rateLimitResetCreditFromJson = RateLimitResetCredit.fromJson(jSONArrayOptJSONArray.optJSONObject(i));
                if (rateLimitResetCreditFromJson != null) {
                    arrayList.add(rateLimitResetCreditFromJson);
                }
            }
        }
        return new ResetCreditsSnapshot(jSONObject.optInt("available_count", 0), arrayList, jSONObject.optLong("fetched_at", 0L));
    }
}
