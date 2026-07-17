package dev.bennett.codexmeter;

import java.util.ArrayList;
import java.util.Iterator;
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
        ArrayList arrayList2 = new ArrayList();
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
                    if (usageWindowFromJson3 != null) {
                        arrayList2.add(usageWindowFromJson3);
                    }
                    if (usageWindowFromJson4 != null) {
                        arrayList2.add(usageWindowFromJson4);
                    }
                }
            }
        }
        UsageWindow usageWindowNearest = nearest(arrayList, FIVE_HOURS, 10800L, 28800L);
        UsageWindow usageWindowNearestExcluding = nearestExcluding(arrayList, WEEK, 432000L, 777600L, usageWindowNearest);
        if (usageWindowNearest == null) {
            if (usageWindow != null && usageWindow != usageWindowNearestExcluding) {
                usageWindowNearest = usageWindow;
            } else if (usageWindowFromJson != null && usageWindowFromJson != usageWindowNearestExcluding) {
                usageWindowNearest = usageWindowFromJson;
            }
        }
        if (usageWindowNearestExcluding != null) {
            usageWindowFromJson = usageWindowNearestExcluding;
        } else if (usageWindowFromJson == null || usageWindowFromJson == usageWindowNearest) {
            usageWindowFromJson = farthestDifferent(arrayList, usageWindowNearest);
        }
        if (usageWindowNearest == null) {
            usageWindowNearest = nearest(arrayList2, FIVE_HOURS, 10800L, 28800L);
        }
        UsageWindow usageWindowNearestExcluding2 = usageWindowFromJson == null ? nearestExcluding(arrayList2, WEEK, 432000L, 777600L, usageWindowNearest) : usageWindowFromJson;
        UsageWindow usageWindow2 = (usageWindowNearest != null || arrayList2.isEmpty()) ? usageWindowNearest : (UsageWindow) arrayList2.get(0);
        if (usageWindowNearestExcluding2 == null) {
            usageWindowNearestExcluding2 = farthestDifferent(arrayList2, usageWindow2);
        }
        JSONObject jSONObjectNullableObject3 = nullableObject(jSONObject, "rate_limit_reset_credits");
        return new UsageSnapshot(strOptString, z, z2, usageWindow2, usageWindowNearestExcluding2, jSONObjectNullableObject3 == null ? -1 : jSONObjectNullableObject3.optInt("available_count", -1), j);
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

    private static UsageWindow farthestDifferent(List<UsageWindow> list, UsageWindow usageWindow) {
        UsageWindow usageWindow2;
        UsageWindow usageWindow3 = null;
        long j = -1;
        Iterator<UsageWindow> it = list.iterator();
        while (true) {
            UsageWindow usageWindow4 = usageWindow3;
            long j2 = j;
            if (it.hasNext()) {
                UsageWindow next = it.next();
                if (next == usageWindow || next.windowSeconds <= j2) {
                    usageWindow2 = usageWindow4;
                } else {
                    j2 = next.windowSeconds;
                    usageWindow2 = next;
                }
                j = j2;
                usageWindow3 = usageWindow2;
            } else {
                return usageWindow4;
            }
        }
    }
}
