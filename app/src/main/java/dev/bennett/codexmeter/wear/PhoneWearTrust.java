package dev.bennett.codexmeter.wear;

import android.content.Context;
import android.util.Log;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import java.util.concurrent.TimeUnit;

/** Confirms Data Layer traffic comes from a node advertising the Wear companion capability. */
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
}
