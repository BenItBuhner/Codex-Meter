package dev.bennett.codexmeter;

/** One-cell Samsung lock/AOD widget for the five-hour allowance. */
public final class SamsungLockFiveHourWidget extends SamsungLockWidgetProvider {
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
        return SamsungLockWidgetSupport.Metric.FIVE_HOUR;
    }
}
