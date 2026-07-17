package dev.bennett.codexmeter;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes.dex */
public final class ResetCreditsParser {
    private ResetCreditsParser() {
    }

    public static ResetCreditsSnapshot parse(String str, long j) throws JSONException {
        if (str == null) {
            str = "{}";
        }
        JSONObject jSONObject = new JSONObject(str);
        ArrayList arrayList = new ArrayList();
        JSONArray jSONArrayOptJSONArray = jSONObject.optJSONArray("credits");
        if (jSONArrayOptJSONArray != null) {
            for (int i = 0; i < jSONArrayOptJSONArray.length(); i++) {
                RateLimitResetCredit rateLimitResetCreditFromApiJson = RateLimitResetCredit.fromApiJson(jSONArrayOptJSONArray.optJSONObject(i));
                if (rateLimitResetCreditFromApiJson != null) {
                    arrayList.add(rateLimitResetCreditFromApiJson);
                }
            }
        }
        return new ResetCreditsSnapshot(jSONObject.optInt("available_count", countAvailable(arrayList)), arrayList, j);
    }

    private static int countAvailable(List<RateLimitResetCredit> list) {
        int i = 0;
        Iterator<RateLimitResetCredit> it = list.iterator();
        while (true) {
            int i2 = i;
            if (it.hasNext()) {
                RateLimitResetCredit next = it.next();
                if (next != null && next.isAvailable()) {
                    i2++;
                }
                i = i2;
            } else {
                return i2;
            }
        }
    }
}
