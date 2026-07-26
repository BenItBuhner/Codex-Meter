package dev.bennett.codexmeter;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes.dex */
public final class UsageParser {
    private static final long FIVE_HOURS = 18000;
    private static final long WEEK = 604800;

    private UsageParser() {
    }

    public static UsageSnapshot parse(String str, long j) throws JSONException {
        boolean z;
        boolean z2;
        UsageWindow usageWindow;
        JSONObject jSONObject = new JSONObject(str);
        String strOptString = jSONObject.optString("plan_type", "");
        JSONObject jSONObjectNullableObject = nullableObject(jSONObject, "rate_limit");
        ArrayList arrayList = new ArrayList();
        ArrayList<UsageLimit> additionalLimits = new ArrayList<>();
        UsageWindow usageWindowFromJson = null;
        if (jSONObjectNullableObject == null) {
            z = true;
            z2 = false;
            usageWindow = null;
        } else {
            boolean zOptBoolean = jSONObjectNullableObject.optBoolean("allowed", true);
            boolean zOptBoolean2 = jSONObjectNullableObject.optBoolean("limit_reached", false);
            UsageWindow usageWindowFromJson2 = UsageWindow.fromJson(nullableObject(jSONObjectNullableObject, "primary_window"));
            usageWindowFromJson = UsageWindow.fromJson(nullableObject(jSONObjectNullableObject, "secondary_window"));
            if (usageWindowFromJson2 != null) {
                arrayList.add(usageWindowFromJson2);
            }
            if (usageWindowFromJson != null) {
                arrayList.add(usageWindowFromJson);
            }
            z = zOptBoolean;
            z2 = zOptBoolean2;
            usageWindow = usageWindowFromJson2;
        }
        JSONArray jSONArrayOptJSONArray = jSONObject.optJSONArray("additional_rate_limits");
        if (jSONArrayOptJSONArray != null) {
            for (int i = 0; i < jSONArrayOptJSONArray.length(); i++) {
                JSONObject jSONObjectOptJSONObject = jSONArrayOptJSONArray.optJSONObject(i);
                if (jSONObjectOptJSONObject != null) {
                    JSONObject jSONObjectNullableObject2 = nullableObject(jSONObjectOptJSONObject, "rate_limit");
                    if (jSONObjectNullableObject2 == null) {
                        jSONObjectNullableObject2 = jSONObjectOptJSONObject;
                    }
                    UsageWindow usageWindowFromJson3 = UsageWindow.fromJson(nullableObject(jSONObjectNullableObject2, "primary_window"));
                    UsageWindow usageWindowFromJson4 = UsageWindow.fromJson(nullableObject(jSONObjectNullableObject2, "secondary_window"));
                    if (usageWindowFromJson3 != null || usageWindowFromJson4 != null) {
                        String name = jSONObjectOptJSONObject.optString("limit_name", "");
                        String feature = jSONObjectOptJSONObject.optString("metered_feature", "");
                        String id = firstNonEmpty(
                                jSONObjectOptJSONObject.optString("limit_id", ""),
                                name, feature, "additional");
                        additionalLimits.add(new UsageLimit(
                                id + "-" + i,
                                name,
                                feature,
                                jSONObjectNullableObject2.optBoolean("allowed", true),
                                jSONObjectNullableObject2.optBoolean("limit_reached", false),
                                usageWindowFromJson3,
                                usageWindowFromJson4));
                    }
                }
            }
        }
        UsageWindow usageWindowNearest = nearest(arrayList, FIVE_HOURS, 10800L, 28800L);
        UsageWindow usageWindowNearestExcluding = nearestExcluding(arrayList, WEEK, 432000L, 777600L, usageWindowNearest);
        JSONObject jSONObjectNullableObject3 = nullableObject(jSONObject, "rate_limit_reset_credits");
        UsageCredits usageCredits = UsageCredits.fromJson(nullableObject(jSONObject, "credits"));
        return new UsageSnapshot(
                strOptString,
                z,
                z2,
                usageWindowNearest,
                usageWindowNearestExcluding,
                additionalLimits,
                usageCredits,
                jSONObjectNullableObject3 == null
                        ? -1 : jSONObjectNullableObject3.optInt("available_count", -1),
                j);
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private static JSONObject nullableObject(JSONObject jSONObject, String str) {
        if (jSONObject == null || jSONObject.isNull(str)) {
            return null;
        }
        return jSONObject.optJSONObject(str);
    }

    private static UsageWindow nearest(List<UsageWindow> list, long j, long j2, long j3) {
        UsageWindow usageWindow;
        long j4;
        UsageWindow usageWindow2 = null;
        long j5 = Long.MAX_VALUE;
        for (UsageWindow usageWindow3 : list) {
            if (usageWindow3.windowSeconds < j2 || usageWindow3.windowSeconds > j3) {
                long j6 = j5;
                usageWindow = usageWindow2;
                j4 = j6;
            } else {
                long jAbs = Math.abs(usageWindow3.windowSeconds - j);
                if (jAbs < j5) {
                    usageWindow = usageWindow3;
                    j4 = jAbs;
                } else {
                    long j7 = j5;
                    usageWindow = usageWindow2;
                    j4 = j7;
                }
            }
            usageWindow2 = usageWindow;
            j5 = j4;
        }
        return usageWindow2;
    }

    private static UsageWindow nearestExcluding(List<UsageWindow> list, long j, long j2, long j3, UsageWindow usageWindow) {
        UsageWindow usageWindow2;
        long j4;
        UsageWindow usageWindow3 = null;
        long j5 = Long.MAX_VALUE;
        for (UsageWindow usageWindow4 : list) {
            if (usageWindow4 == usageWindow || usageWindow4.windowSeconds < j2 || usageWindow4.windowSeconds > j3) {
                long j6 = j5;
                usageWindow2 = usageWindow3;
                j4 = j6;
            } else {
                long jAbs = Math.abs(usageWindow4.windowSeconds - j);
                if (jAbs < j5) {
                    usageWindow2 = usageWindow4;
                    j4 = jAbs;
                } else {
                    long j7 = j5;
                    usageWindow2 = usageWindow3;
                    j4 = j7;
                }
            }
            usageWindow3 = usageWindow2;
            j5 = j4;
        }
        return usageWindow3;
    }

}
