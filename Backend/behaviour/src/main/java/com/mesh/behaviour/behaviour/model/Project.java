package com.mesh.behaviour.behaviour.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String username;
    private String projectName;

    private String s3Prefix;

    private String driverKey;   // canonical driver (usually vN/driver.py)
    private String testsKey;    // canonical tests (tests/tests.yaml)

    private Boolean approved;

    // NEW: track which version is active
    private Integer currentVersion;  // starts at 0 (base upload)
}
