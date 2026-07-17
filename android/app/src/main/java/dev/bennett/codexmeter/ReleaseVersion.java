package dev.bennett.codexmeter;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Small SemVer-compatible comparator for GitHub release tags. */
public final class ReleaseVersion implements Comparable<ReleaseVersion> {
    private final List<BigInteger> numbers;
    private final String prerelease;
    private final String normalized;

    private ReleaseVersion(List<BigInteger> numbers, String prerelease, String normalized) {
        this.numbers = numbers;
        this.prerelease = prerelease;
        this.normalized = normalized;
    }

    public static ReleaseVersion parse(String value) {
        if (value == null) {
            return null;
        }
        String candidate = value.trim();
        if (candidate.startsWith("v") || candidate.startsWith("V")) {
            candidate = candidate.substring(1);
        }
        int buildIndex = candidate.indexOf('+');
        if (buildIndex >= 0) {
            candidate = candidate.substring(0, buildIndex);
        }
        String prerelease = "";
        int prereleaseIndex = candidate.indexOf('-');
        if (prereleaseIndex >= 0) {
            prerelease = candidate.substring(prereleaseIndex + 1);
            candidate = candidate.substring(0, prereleaseIndex);
        }
        String[] parts = candidate.split("\\.", -1);
        if (parts.length < 2 || parts.length > 4 || !validPrerelease(prerelease)) {
            return null;
        }
        ArrayList<BigInteger> numbers = new ArrayList<>();
        StringBuilder normalized = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty() || !digits(part)) {
                return null;
            }
            BigInteger number = new BigInteger(part);
            numbers.add(number);
            if (normalized.length() > 0) {
                normalized.append('.');
            }
            normalized.append(number);
        }
        while (numbers.size() < 3) {
            numbers.add(BigInteger.ZERO);
            normalized.append(".0");
        }
        if (!prerelease.isEmpty()) {
            normalized.append('-').append(prerelease.toLowerCase(Locale.US));
        }
        return new ReleaseVersion(numbers, prerelease.toLowerCase(Locale.US), normalized.toString());
    }

    public static int compare(String left, String right) {
        ReleaseVersion leftVersion = parse(left);
        ReleaseVersion rightVersion = parse(right);
        if (leftVersion == null || rightVersion == null) {
            throw new IllegalArgumentException("Invalid release version.");
        }
        return leftVersion.compareTo(rightVersion);
    }

    public String normalized() {
        return normalized;
    }

    public boolean isPrerelease() {
        return !prerelease.isEmpty();
    }

    @Override
    public int compareTo(ReleaseVersion other) {
        int count = Math.max(numbers.size(), other.numbers.size());
        for (int index = 0; index < count; index++) {
            BigInteger left = index < numbers.size() ? numbers.get(index) : BigInteger.ZERO;
            BigInteger right = index < other.numbers.size() ? other.numbers.get(index) : BigInteger.ZERO;
            int compared = left.compareTo(right);
            if (compared != 0) {
                return compared;
            }
        }
        if (prerelease.isEmpty() != other.prerelease.isEmpty()) {
            return prerelease.isEmpty() ? 1 : -1;
        }
        if (prerelease.isEmpty()) {
            return 0;
        }
        String[] leftParts = prerelease.split("\\.");
        String[] rightParts = other.prerelease.split("\\.");
        int countParts = Math.max(leftParts.length, rightParts.length);
        for (int index = 0; index < countParts; index++) {
            if (index >= leftParts.length) {
                return -1;
            }
            if (index >= rightParts.length) {
                return 1;
            }
            String left = leftParts[index];
            String right = rightParts[index];
            boolean leftNumeric = digits(left);
            boolean rightNumeric = digits(right);
            int compared;
            if (leftNumeric && rightNumeric) {
                compared = new BigInteger(left).compareTo(new BigInteger(right));
            } else if (leftNumeric != rightNumeric) {
                compared = leftNumeric ? -1 : 1;
            } else {
                compared = left.compareTo(right);
            }
            if (compared != 0) {
                return compared;
            }
        }
        return 0;
    }

    private static boolean digits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) < '0' || value.charAt(index) > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean validPrerelease(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character >= 'a' && character <= 'z')
                    && !(character >= 'A' && character <= 'Z')
                    && !(character >= '0' && character <= '9')
                    && character != '.' && character != '-') {
                return false;
            }
        }
        return !value.startsWith(".") && !value.endsWith(".") && !value.contains("..");
    }
}
