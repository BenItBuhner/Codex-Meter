package dev.bennett.codexmeter;

import java.util.List;

/** The dashboard's floating provider switcher in the One UI 9 and Liquid Glass designs. */
interface ProviderBar {
    /**
     * Shows the Priority tab (when {@code withPriority}) and the {@code enabled} providers in order,
     * with the Priority tab selected when {@code onPriority}, else {@code current}.
     */
    void update(List<UsageProvider> enabled, UsageProvider current, boolean withPriority, boolean onPriority);
}
