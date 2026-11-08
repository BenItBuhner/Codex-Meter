package dev.bennett.codexmeter;

/**
 * Where the in-app update is, shared by the One UI screen (UpdateActivity) and the Liquid Glass one
 * (GlassUpdateScreen). It replaces separate "checking", "operation running", "waiting for
 * permission" and "start install pending" flags, which could disagree.
 *
 * <pre>
 *   IDLE ──check()──▶ CHECKING ──checked()──▶ IDLE
 *   IDLE ──askPermission()──▶ AWAITING_PERMISSION ──permissionDeclined()──▶ IDLE
 *                                       └──returnedWithPermission()──▶ IDLE (then install)
 *   IDLE ──install()──▶ INSTALLING ──installed()──▶ IDLE
 * </pre>
 *
 * Each transition returns false and changes nothing when it doesn't apply, so a second tap or a
 * late callback can't start a second download or check. Separately, a one-shot "install when the
 * release is known" request comes from the update notification's Install action.
 *
 * <p>Pure Java so it can be tested off-device. Used on the main thread only.
 */
final class UpdateFlow {
    enum Phase { IDLE, CHECKING, AWAITING_PERMISSION, INSTALLING }

    private Phase phase = Phase.IDLE;
    private boolean autoInstall;

    Phase phase() {
        return this.phase;
    }

    /** True while a check or a download is running: the install button is disabled. */
    boolean busy() {
        return this.phase == Phase.CHECKING || this.phase == Phase.INSTALLING;
    }

    boolean check() {
        return move(Phase.IDLE, Phase.CHECKING);
    }

    boolean checked() {
        return move(Phase.CHECKING, Phase.IDLE);
    }

    boolean askPermission() {
        return move(Phase.IDLE, Phase.AWAITING_PERMISSION);
    }

    /** The permission dialog was cancelled, or the settings screen couldn't open. */
    boolean permissionDeclined() {
        return move(Phase.AWAITING_PERMISSION, Phase.IDLE);
    }

    /**
     * The screen came back. True, and back to idle, when it was waiting for the install
     * permission and now has it: the caller then starts the install.
     */
    boolean returnedWithPermission(boolean canInstall) {
        return canInstall && move(Phase.AWAITING_PERMISSION, Phase.IDLE);
    }

    boolean install() {
        return move(Phase.IDLE, Phase.INSTALLING);
    }

    boolean installed() {
        return move(Phase.INSTALLING, Phase.IDLE);
    }

    /** The screen was opened to install right away (the notification's Install action). */
    void requestAutoInstall(boolean value) {
        this.autoInstall = value;
    }

    /** Takes the one-shot install request: true at most once per request. */
    boolean takeAutoInstall() {
        boolean value = this.autoInstall;
        this.autoInstall = false;
        return value;
    }

    private boolean move(Phase from, Phase to) {
        if (this.phase != from) {
            return false;
        }
        this.phase = to;
        return true;
    }
}
