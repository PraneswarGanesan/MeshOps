package com.mesh.behaviour.behaviour.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mesh.behaviour.behaviour.model.BehaviorTestCaseResult;
import com.mesh.behaviour.behaviour.model.Metric;
import com.mesh.behaviour.behaviour.model.Run;
import com.mesh.behaviour.behaviour.repository.BehaviorTestCaseResultRepository;
import com.mesh.behaviour.behaviour.repository.MetricRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ResultsIngestService {

    private final S3Service s3;
    private final MetricRepository metricRepo;
    private final BehaviorTestCaseResultRepository testRepo;
    private final ObjectMapper om = new ObjectMapper();

    /**
     * Reads metrics.json and tests.csv from S3 for a completed run
     * and persists them into Metrics + BehaviorTestCaseResult tables.
     */
    public void ingestRunArtifacts(Run run) {
        String prefix = run.getArtifactsPrefix(); // already "username/project/artifacts-behaviour/run_X"

        // ---- metrics.json -> Metric rows ----
        String metricsKey = prefix + "/metrics.json";
        if (s3.exists(metricsKey)) {
            try {
                String json = s3.getString(metricsKey);
                JsonNode node = om.readTree(json);
                List<Metric> rows = new ArrayList<>();
                node.fields().forEachRemaining(e -> {
                    if (e.getValue().isNumber()) {
                        rows.add(Metric.builder()
                                .username(run.getUsername())
                                .projectName(run.getProjectName())
                                .runId(run.getId())
                                .name(e.getKey())         // e.g., accuracy, f1, recall
                                .value(e.getValue().asDouble())
                                .build());
                    }
                });
                metricRepo.saveAll(rows);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // ---- tests.csv -> BehaviorTestCaseResult rows ----
        String testsKey = prefix + "/tests.csv";
        if (s3.exists(testsKey)) {
            try {
                String csv = s3.getString(testsKey);
                List<BehaviorTestCaseResult> rows = parseTestsCsv(run, csv);
                testRepo.saveAll(rows);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Parse tests.csv into BehaviorTestCaseResult rows.
     * Expected format:
     * name,category,severity,result,value,threshold,metric(optional)
     */
    private List<BehaviorTestCaseResult> parseTestsCsv(Run run, String csv) throws Exception {
        List<BehaviorTestCaseResult> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new StringReader(csv))) {
            String header = br.readLine(); // read header line
            if (header == null) return out;
            String line;
            while ((line = br.readLine()) != null) {
                String[] t = line.split(",", -1);
                if (t.length < 6) continue;

                boolean passed = "PASS".equalsIgnoreCase(t[3].trim());
                boolean skipped = "SKIP".equalsIgnoreCase(t[3].trim());

                // If CSV has a metric column (7th col), use it, else default to "accuracy"
                String metric = (t.length >= 7 && !t[6].trim().isEmpty()) ? t[6].trim() : "accuracy";

                out.add(BehaviorTestCaseResult.builder()
                        .username(run.getUsername())
                        .projectName(run.getProjectName())
                        .runId(run.getId())
                        .name(t[0].trim())
                        .category(t[1].trim())
                        .severity(t[2].trim())
                        .passed(passed)
                        .skipped(skipped)
                        .metric(metric)
                        .value(safeDouble(t[4].trim()))
                        .threshold(safeDouble(t[5].trim()))
                        .build());
            }
        }
        return out;
    }

    private Double safeDouble(String s) {
        try {
            return s.isBlank() ? null : Double.parseDouble(s);
        } catch (Exception e) {
            return null;
        }
    }
}
