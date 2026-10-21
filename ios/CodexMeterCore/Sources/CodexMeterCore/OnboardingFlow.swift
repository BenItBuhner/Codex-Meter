import Foundation

/// First-run setup: welcome, pick providers, connect each one, done.
public enum OnboardingStep: Equatable, Hashable, Sendable {
    case welcome
    case providers
    case connect(UsageProvider)
    case done
}

public enum OnboardingFlow {
    /// Every step for the providers chosen so far, in order. Before anything is chosen the
    /// flow is welcome, providers, done.
    public static func steps(for chosen: [UsageProvider]) -> [OnboardingStep] {
        [.welcome, .providers] + chosen.map { .connect($0) } + [.done]
    }

    /// Existing installs (anything signed in, demo mode, or cached usage) skip setup; only a
    /// fresh install that never finished it sees it.
    public static func shouldShow(completed: Bool, hasExistingSetup: Bool) -> Bool {
        !completed && !hasExistingSetup
    }

    /// The step after `step`, or `step` itself when it is the last one or isn't in the flow.
    public static func next(after step: OnboardingStep, in steps: [OnboardingStep]) -> OnboardingStep {
        guard let index = steps.firstIndex(of: step), index + 1 < steps.count else { return step }
        return steps[index + 1]
    }

    /// The step before `step`, or `.welcome`.
    public static func previous(before step: OnboardingStep, in steps: [OnboardingStep]) -> OnboardingStep {
        guard let index = steps.firstIndex(of: step), index > 0 else { return .welcome }
        return steps[index - 1]
    }
}
