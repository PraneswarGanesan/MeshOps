package com.mesh.unittest.util;

public class S3KeyUtil {
    
    public static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (parts[i] == null || parts[i].isEmpty()) continue;
            
            String part = parts[i];
            if (i > 0 && !part.startsWith("/") && !sb.toString().endsWith("/")) {
                sb.append("/");
            }
            sb.append(part);
        }
        return sb.toString().replaceAll("/+", "/");
    }
    
    public static String keyOf(String path) {
        if (path == null) return "";
        return path.startsWith("/") ? path.substring(1) : path;
    }
    
    public static String buildVersionPath(String username, String projectName, String version) {
        return join(username, projectName, "artifacts", "versions", version);
    }
    
    public static String buildUnitRunPath(String username, String projectName, String version, Long runId) {
        return join(buildVersionPath(username, projectName, version), "unit", "runs", "run_" + runId);
    }
    
    public static String buildUnitTestArchivePath(String username, String projectName, String version, String timestamp) {
        return join(buildVersionPath(username, projectName, version), "unit-test-tests", "tests_" + timestamp + ".yaml");
    }
}
