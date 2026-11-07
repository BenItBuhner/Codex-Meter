package dev.bennett.codexmeter;

/**
 * Pure onboarding navigation rules shared by the activity and JVM self-tests.
 *
 * <p>Steps: welcome, choose providers, one sign-in step per chosen provider, then done. The
 * number of sign-in steps depends on how many providers were chosen ({@code chosen}).
 */
public final class OnboardingFlow {
    public static final int STEP_WELCOME = 0;
    public static final int STEP_PROVIDERS = 1;
    public static final int FIRST_SIGN_IN_STEP = 2;

    public static final int LAUNCH_MAIN = 0;
    public static final int LAUNCH_ONBOARDING = 1;
    public static final int LAUNCH_MAIN_AND_COMPLETE = 2;

    private OnboardingFlow() {
    }

    /**
     * Where a launch goes. Installs that were already signed in before setup existed skip it,
     * but a setup the user has started (or is returning to from the browser) always resumes.
     */
    public static int launchAction(boolean completed, boolean signedIn, boolean oauthReturn,
            boolean inProgress) {
        if (completed) return LAUNCH_MAIN;
        if (oauthReturn || inProgress || !signedIn) return LAUNCH_ONBOARDING;
        return LAUNCH_MAIN_AND_COMPLETE;
    }

    public static int stepCount(int chosen) {
        return Math.max(chosen, 0) + 3;
    }

    public static int completeStep(int chosen) {
        return stepCount(chosen) - 1;
    }

    /** Index into the chosen providers for a sign-in step, or -1 for any other step. */
    public static int signInIndex(int step, int chosen) {
        int index = step - FIRST_SIGN_IN_STEP;
        return index >= 0 && index < Math.max(chosen, 0) ? index : -1;
    }

    /** A saved step, kept when it still exists. Coming back from the browser keeps it too. */
    public static int initialStep(int savedStep, int chosen) {
        return normalizeStep(savedStep, chosen);
    }

    public static int normalizeStep(int step, int chosen) {
        if (step < STEP_WELCOME || step > completeStep(chosen)) return STEP_WELCOME;
        // Nothing chosen yet: stay on the provider picker rather than skipping to done.
        if (chosen <= 0 && step > STEP_PROVIDERS) return STEP_PROVIDERS;
        return step;
    }

    public static int nextStep(int step, int chosen) {
        return normalizeStep(Math.min(normalizeStep(step, chosen) + 1, completeStep(chosen)),
                chosen);
    }

    public static int previousStep(int step, int chosen) {
        return Math.max(normalizeStep(step, chosen) - 1, STEP_WELCOME);
    }
}
