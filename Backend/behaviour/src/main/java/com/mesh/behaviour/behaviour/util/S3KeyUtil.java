package com.mesh.behaviour.behaviour.util;

/** Small helpers to work with S3 URIs and keys. */
public final class S3KeyUtil {
    private S3KeyUtil() {}

    /** Extract bucket from s3://bucket/prefix */
    public static String bucketOf(String s3Uri) {
        if (s3Uri == null || !s3Uri.startsWith("s3://")) {
            throw new IllegalArgumentException("Invalid S3 URI: " + s3Uri);
        }
        String trimmed = s3Uri.substring("s3://".length());
        int slash = trimmed.indexOf('/');
        return (slash < 0) ? trimmed : trimmed.substring(0, slash);
    }

    /** Extract key prefix from s3://bucket/prefix (no leading slash). */
    public static String keyOf(String s3Uri) {
        if (s3Uri == null || !s3Uri.startsWith("s3://")) {
            throw new IllegalArgumentException("Invalid S3 URI: " + s3Uri);
        }
        String trimmed = s3Uri.substring("s3://".length());
        int slash = trimmed.indexOf('/');
        return (slash < 0) ? "" : trimmed.substring(slash + 1);
    }

    /** Safe join for S3 keys (avoids duplicate slashes). */
    public static String join(String left, String right) {
        if (left == null || left.isBlank()) {
            return right == null ? "" : right.replaceAll("^/+", "");
        }
        if (right == null || right.isBlank()) {
            return left.replaceAll("/+$", "");
        }
        return left.replaceAll("/+$", "") + "/" + right.replaceAll("^/+", "");
    }

    /** Build artifacts prefix: <baseKey>/artifacts/run_<runId>/ */
    public static String artifactsPrefix(String baseKey, long runId) {
        return join(join(baseKey, "artifacts"), "run_" + runId + "/");
    }
    public static String join(String... parts) {
        if (parts == null || parts.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isBlank()) continue;
            String clean = p.trim();
            // Remove leading/trailing slashes
            while (clean.startsWith("/")) clean = clean.substring(1);
            while (clean.endsWith("/")) clean = clean.substring(0, clean.length() - 1);
            if (!clean.isEmpty()) {
                if (sb.length() > 0) sb.append("/");
                sb.append(clean);
            }
        }
        return sb.toString();
    }

    // Standard artifact filenames under the artifacts prefix
    public static String metricsJson(String artifactsPrefix) { return join(artifactsPrefix, "metrics.json"); }
    public static String testsCsv(String artifactsPrefix)    { return join(artifactsPrefix, "tests.csv"); }
    public static String confMatrixPng(String artifactsPrefix){ return join(artifactsPrefix, "confusion_matrix.png"); }
    public static String logsTxt(String artifactsPrefix)     { return join(artifactsPrefix, "logs.txt"); }
}
