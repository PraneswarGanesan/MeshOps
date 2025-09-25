package com.mesh.behaviour.behaviour.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "scenario_results")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScenarioResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String username;
    private String projectName;

    // 🔹 OPTIONAL: add versionLabel if you want results per version
    private String versionLabel;

    private Long runId;   // link to the run

    private String name;       // scenario_<i> or filename
    private String expected;   // expected output/label
    private String predicted;  // predicted output/label

    private String category;   // always "Scenario"
    private String severity;   // "high"/"medium"
    private String result;     // PASS / FAIL
    private String metric;     // e.g. accuracy, mae
    private Double threshold;  // "-" or numeric
    private Double value;      // predicted numeric (for regression)

    private boolean passed;
    private boolean skipped;
}
