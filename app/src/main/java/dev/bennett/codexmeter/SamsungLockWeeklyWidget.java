package dev.bennett.codexmeter;

/** One-cell Samsung lock/AOD widget for the weekly allowance. */
public final class SamsungLockWeeklyWidget extends SamsungLockWidgetProvider {
    @Override
    protected SamsungLockWidgetSupport.Shape shape() {
        return SamsungLockWidgetSupport.Shape.SQUARE;
    }

    @Override
    protected SamsungLockWidgetSupport.Style style() {
        return SamsungLockWidgetSupport.Style.DIALS;
    }

    @Override
    protected SamsungLockWidgetSupport.Metric metric() {
        return SamsungLockWidgetSupport.Metric.WEEKLY;
    }
}
