package dev.bennett.codexmeter;

import java.util.List;

/** The dashboard's floating provider switcher in the One UI 9 and Liquid Glass designs. */
interface ProviderBar {
    /** Shows {@code enabled} providers in order with {@code current} selected. */
    void update(List<UsageProvider> enabled, UsageProvider current);
}
