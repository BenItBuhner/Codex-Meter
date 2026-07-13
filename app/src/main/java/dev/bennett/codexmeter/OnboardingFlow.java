package dev.bennett.codexmeter;

/** Pure onboarding navigation rules shared by the activity and JVM self-tests. */
public final class OnboardingFlow {
    public static final int STEP_WELCOME = 0;
    public static final int STEP_USAGE = 1;
    public static final int STEP_ACCOUNT = 2;
    public static final int STEP_COMPLETE = 3;
    public static final int STEP_COUNT = 4;

    public static final int LAUNCH_MAIN = 0;
    public static final int LAUNCH_ONBOARDING = 1;
    public static final int LAUNCH_MAIN_AND_COMPLETE = 2;

    private OnboardingFlow() {
    }

    public static int launchAction(boolean completed, boolean signedIn, boolean oauthReturn) {
        if (completed) return LAUNCH_MAIN;
        if (oauthReturn || !signedIn) return LAUNCH_ONBOARDING;
        return LAUNCH_MAIN_AND_COMPLETE;
    }

    public static int initialStep(int savedStep, boolean signedIn, boolean oauthReturn) {
        if (signedIn) return STEP_COMPLETE;
        if (oauthReturn) return STEP_ACCOUNT;
        return normalizeStep(savedStep);
    }

    public static int normalizeStep(int step) {
        return step >= STEP_WELCOME && step < STEP_COUNT ? step : STEP_WELCOME;
    }

    public static int nextStep(int step) {
        return Math.min(normalizeStep(step) + 1, STEP_COMPLETE);
    }

    public static int previousStep(int step) {
        return Math.max(normalizeStep(step) - 1, STEP_WELCOME);
    }
}
