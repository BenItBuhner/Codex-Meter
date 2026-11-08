package dev.bennett.codexmeter;

/** The in-app update's phases: no double checks or downloads, and permission round trips. */
public final class UpdateFlowSelfTest {
    public static void main(String[] args) {
        checkThenInstall();
        noDoubleStarts();
        permissionRoundTrip();
        permissionDeclinedStaysIdle();
        autoInstallIsOneShot();
        System.out.println("Update flow: one check or download at a time, permission and auto-install handled once.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void checkThenInstall() {
        UpdateFlow f = new UpdateFlow();
        check(f.check() && f.busy(), "checking is busy");
        check(f.checked() && !f.busy(), "back to idle after the check");
        check(f.install() && f.busy(), "installing is busy");
        check(f.installed() && f.phase() == UpdateFlow.Phase.IDLE, "idle after the hand-off");
    }

    private static void noDoubleStarts() {
        UpdateFlow f = new UpdateFlow();
        f.install();
        check(!f.install(), "a second tap doesn't start a second download");
        check(!f.check(), "no check during a download");
        check(!f.checked(), "a stray check result doesn't end the download");
        f.installed();
        check(!f.installed(), "ending twice is harmless");
    }

    private static void permissionRoundTrip() {
        UpdateFlow f = new UpdateFlow();
        check(f.askPermission(), "waits for permission");
        check(!f.install(), "no download while waiting for permission");
        check(!f.returnedWithPermission(false), "coming back without permission keeps waiting");
        check(f.phase() == UpdateFlow.Phase.AWAITING_PERMISSION, "still waiting");
        check(f.returnedWithPermission(true), "coming back with permission starts the install");
        check(f.install(), "and the install can begin");
    }

    private static void permissionDeclinedStaysIdle() {
        UpdateFlow f = new UpdateFlow();
        f.askPermission();
        check(f.permissionDeclined(), "cancel ends the wait");
        check(!f.returnedWithPermission(true), "a later grant elsewhere doesn't start an install");
    }

    private static void autoInstallIsOneShot() {
        UpdateFlow f = new UpdateFlow();
        f.requestAutoInstall(true);
        check(f.takeAutoInstall(), "the notification's Install action is taken once");
        check(!f.takeAutoInstall(), "and only once");
    }
}
