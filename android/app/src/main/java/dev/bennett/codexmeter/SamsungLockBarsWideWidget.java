package dev.bennett.codexmeter;

import dev.bennett.codexmeter.SamsungLockWidgetSupport;

/* JADX INFO: loaded from: classes.dex */
public final class SamsungLockBarsWideWidget extends SamsungLockWidgetProvider {
    @Override // dev.bennett.codexmeter.SamsungLockWidgetProvider
    protected SamsungLockWidgetSupport.Shape shape() {
        return SamsungLockWidgetSupport.Shape.WIDE;
    }

    @Override // dev.bennett.codexmeter.SamsungLockWidgetProvider
    protected SamsungLockWidgetSupport.Style style() {
        return SamsungLockWidgetSupport.Style.BARS;
    }
}
