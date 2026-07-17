package dev.bennett.codexmeter.wear;

import android.content.Context;
import android.util.Log;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Confirms Data Layer traffic is from the Codex Wear companion.
 *
 * Wearable Data Layer items/messages are already private to the same applicationId and
 * signing certificate across nodes. Capability checks alone are not enough (any app can
 * advertise a string), so we also require an explicit package_name claim that matches this
 * installed package.
 */
public final class PhoneWearTrust {
    private static final String TAG = "CodexWearTrust";
    private static final long CAPABILITY_TIMEOUT_MS = 2500L;

    private PhoneWearTrust() {
    }

    public static boolean isTrustedWearNode(Context context, String nodeId) {
        if (context == null || nodeId == null || nodeId.isEmpty()) return false;
        try {
            CapabilityInfo info = Tasks.await(
                    Wearable.getCapabilityClient(context.getApplicationContext())
                            .getCapability(WearSyncPaths.CAPABILITY_WEAR,
                                    CapabilityClient.FILTER_ALL),
                    CAPABILITY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (info == null || info.getNodes() == null) return false;
            for (Node node : info.getNodes()) {
                if (node != null && nodeId.equals(node.getId())) {
                    return true;
                }
            }
            return false;
        } catch (Exception exception) {
            Log.w(TAG, "Could not verify Wear capability for node " + nodeId, exception);
            return false;
        }
    }

    public static boolean isTrustedWearMessage(Context context, String nodeId, byte[] data) {
        return isTrustedWearNode(context, nodeId) && packageMatches(context, decodePackage(data));
    }

    public static boolean isTrustedWearSettings(Context context, String nodeId,
            WearSettingsState remote) {
        return isTrustedWearNode(context, nodeId)
                && remote != null
                && WearSettingsState.SOURCE_WEAR.equals(remote.sourceNode)
                && packageMatches(context, remote.packageName);
    }

    public static byte[] messageAuthPayload(Context context) {
        String packageName = context == null ? "" : context.getPackageName();
        return packageName.getBytes(StandardCharsets.UTF_8);
    }

    private static boolean packageMatches(Context context, String claimedPackage) {
        if (context == null || claimedPackage == null || claimedPackage.isEmpty()) return false;
        return claimedPackage.equals(context.getPackageName());
    }

    private static String decodePackage(byte[] data) {
        if (data == null || data.length == 0) return "";
        return new String(data, StandardCharsets.UTF_8).trim();
    }
}
