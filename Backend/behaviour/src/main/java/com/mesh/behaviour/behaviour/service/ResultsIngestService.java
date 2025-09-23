package com.mesh.behaviour.behaviour.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mesh.behaviour.behaviour.model.Run;
import com.mesh.behaviour.behaviour.model.Project;
import com.mesh.behaviour.behaviour.repository.ProjectRepository;
import com.mesh.behaviour.behaviour.repository.RunRepository;
import com.mesh.behaviour.behaviour.util.S3KeyUtil;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Ingests artifacts produced by the runner and persists/update Run state.
 *
 * Assumptions:
 * - runner uploads files to S3 under run.getArtifactsPrefix() (e.g. "pg/foo/artifacts-behaviour/run_123")
 * - S3Service has methods: exists(key), getString(key), putString(key, content, mime), copy(srcKey, dstKey)
 *
 * Extend this class if you have extra Run fields (metrics JSON column, artifacts table, etc.).
 */
@Service
@RequiredArgsConstructor
public class ResultsIngestService {

    private static final Logger log = LoggerFactory.getLogger(ResultsIngestService.class);

    private final S3Service s3;
    private final RunRepository runRepository;
    private final ProjectRepository projectRepository;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Ingest run artifacts for the given run.
     * - Reads status.json (if present) and sets run flags accordingly.
     * - Attempts to read metrics.json and tests.csv and copies them into the versioned folder if available.
     *
     * Returns a small summary map that callers can log or return to UI.
     */
    public Map<String, Object> ingestRunArtifacts(Run run) {
        Map<String, Object> out = new HashMap<>();
        if (run == null) throw new IllegalArgumentException("run is null");

        String artifactsPrefix = run.getArtifactsPrefix(); // expected "username/project/artifacts-behaviour/run_<id>"
        if (artifactsPrefix == null || artifactsPrefix.isBlank()) {
            log.warn("Run {} has no artifactsPrefix set; nothing to ingest", run.getId());
            out.put("ingested", false);
            out.put("reason", "no_artifacts_prefix");
            return out;
        }

        try {
            // 1) Read status.json if available
            String statusKey = artifactsPrefix + "/status.json";
            boolean statusExists = s3.exists(statusKey);
            boolean success = false;
            Instant finishedAt = null;

            if (statusExists) {
                try {
                    String statusJson = s3.getString(statusKey);
                    JsonNode st = mapper.readTree(statusJson);
                    if (st.has("success")) success = st.get("success").asBoolean(false);
                    if (st.has("timestamp")) {
                        try {
                            finishedAt = Instant.parse(st.get("timestamp").asText());
                        } catch (Exception e) {
                            // ignore parse error
                            finishedAt = Instant.now();
                        }
                    } else {
                        finishedAt = Instant.now();
                    }
                    out.put("statusJson", statusJson);
                } catch (Exception e) {
                    log.warn("Failed to parse status.json for run {}: {}", run.getId(), e.getMessage());
                }
            } else {
                // fallback: no status.json — assume success if metrics.json exists
                String metricsKey = artifactsPrefix + "/metrics.json";
                if (s3.exists(metricsKey)) success = true;
            }

            // 2) Read metrics.json (optional)
            String metricsKey = artifactsPrefix + "/metrics.json";
            if (s3.exists(metricsKey)) {
                try {
                    String metrics = s3.getString(metricsKey);
                    out.put("metricsJson", metrics);
                } catch (Exception e) {
                    log.warn("Failed to read metrics.json for run {}: {}", run.getId(), e.getMessage());
                }
            }

            // 3) Read tests.csv (optional)
            String testsKey = artifactsPrefix + "/tests.csv";
            if (s3.exists(testsKey)) {
                try {
                    String testsCsv = s3.getString(testsKey);
                    out.put("testsCsv", testsCsv.length() > 10000 ? testsCsv.substring(0,10000) + "...(truncated)" : testsCsv);
                } catch (Exception e) {
                    log.warn("Failed to read tests.csv for run {}: {}", run.getId(), e.getMessage());
                }
            }

            // 4) Persist to Run: mark done/success/finishedAt. Save commandId/instance are assumed set already.
            run.setIsDone(true);
            run.setIsRunning(false);
            run.setIsSuccess(success);
            if (finishedAt == null) finishedAt = Instant.now();
            run.setFinishedAt(Timestamp.from(finishedAt));
            runRepository.save(run);

            // 5) If the run references a version (saved in run.getVersionName or project mapping) copy artifacts there
            //    We will try to find the project's version prefix to mirror the run outputs under that version path.
            //    Format: artifacts/versions/<version>/runs-behaviour/run_<id>/
            String versionName = run.getVersionName(); // recommended to persist; if absent, attempt to derive from project default
            String projectPrefix = null;
            if (versionName != null && !versionName.isBlank()) {
                // Expect run.getArtifactsPrefix() like "username/project/artifacts-behaviour/run_123"
                // Need baseKey: "username/project" to compute versions base
                String[] parts = run.getArtifactsPrefix().split("/");
                if (parts.length >= 2) {
                    projectPrefix = parts[0] + "/" + parts[1];
                }
            } else {
                // try to find project and infer
                try {
                    Project project = projectRepository.findByUsernameAndProjectName(run.getUsername(), run.getProjectName()).orElse(null);
                    if (project != null) {
                        String s3Prefix = project.getS3Prefix(); // e.g. "s3://bucket/username/project/"
                        if (s3Prefix != null) {
                            String key = S3KeyUtil.keyOf(s3Prefix); // helper returns username/project/...
                            // find highest version under key + "artifacts/versions/"
                            versionName = findLatestVersionForProject(key);
                        }
                    }
                } catch (Exception e) {
                    // ignore
                }
            }

            if (versionName != null && !versionName.isBlank() && projectPrefix != null) {
                // copy selected artifacts into version folder under runs-behaviour
                String versionRunsPrefix = S3KeyUtil.join(projectPrefix, "artifacts/versions/" + versionName + "/runs-behaviour/run_" + run.getId());
                // ensure trailing slash
                if (!versionRunsPrefix.endsWith("/")) versionRunsPrefix = versionRunsPrefix + "/";
                // Copy the important files (if present)
                copyIfExists(artifactsPrefix + "/metrics.json", versionRunsPrefix + "/metrics.json");
                copyIfExists(artifactsPrefix + "/tests.csv", versionRunsPrefix + "/tests.csv");
                copyIfExists(artifactsPrefix + "/confusion_matrix.png", versionRunsPrefix + "/confusion_matrix.png");
                copyIfExists(artifactsPrefix + "/logs.txt", versionRunsPrefix + "/logs.txt");
                copyIfExists(artifactsPrefix + "/manifest.json", versionRunsPrefix + "/manifest.json");
                copyIfExists(artifactsPrefix + "/status.json", versionRunsPrefix + "/status.json");
                out.put("copiedToVersion", versionRunsPrefix);
            } else {
                log.info("No versionName/projectPrefix available for run {} — skipping copy to version folder", run.getId());
            }

            out.put("ingested", true);
            out.put("success", success);
            out.put("finishedAt", finishedAt.toString());
            return out;

        } catch (Exception e) {
            log.error("Ingest failed for run {}: {}", run.getId(), e.getMessage(), e);
            out.put("ingested", false);
            out.put("reason", e.getMessage());
            return out;
        }
    }

    private void copyIfExists(String srcKey, String dstKey) {
        try {
            if (s3.exists(srcKey)) {
                // If your S3 service has a copy API, use it; otherwise read and re-put
                try {
                    s3.copy(srcKey, dstKey);
                    log.info("Copied {} -> {}", srcKey, dstKey);
                } catch (UnsupportedOperationException u) {
                    // fallback to read + write
                    String content = s3.getString(srcKey);
                    String mime = guessMimeFromKey(srcKey);
                    s3.putString(dstKey, content, mime);
                    log.info("Copied (via get+put) {} -> {}", srcKey, dstKey);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to copy {} -> {}: {}", srcKey, dstKey, e.getMessage());
        }
    }

    private String guessMimeFromKey(String key) {
        if (key.endsWith(".json")) return "application/json";
        if (key.endsWith(".csv")) return "text/csv";
        if (key.endsWith(".png")) return "image/png";
        if (key.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    /**
     * Try to find the latest version folder for a project baseKey.
     * Implementation depends on your S3Service; for now this attempts to list keys under baseKey + "/artifacts/versions/"
     * and find the highest vN. If not found returns null.
     *
     * TODO: if S3Service provides a listPrefixes method use that directly.
     */
    private String findLatestVersionForProject(String baseKey) {
        try {
            String versionsBase = S3KeyUtil.join(baseKey, "artifacts/versions") + "/";
            var keys = s3.listKeys(versionsBase);
            int max = -1;
            String best = null;
            for (String k : keys) {
                String after = k.startsWith(versionsBase) ? k.substring(versionsBase.length()) : k;
                if (after.isEmpty()) continue;
                String[] parts = after.split("/");
                String candidate = parts[0];
                if (candidate != null && candidate.matches("v\\d+")) {
                    int n = Integer.parseInt(candidate.substring(1));
                    if (n > max) { max = n; best = candidate; }
                }
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }
}
