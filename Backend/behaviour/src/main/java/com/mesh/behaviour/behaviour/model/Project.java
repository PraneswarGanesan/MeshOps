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

    private String driverKey;
    private String testsKey;

    private Boolean approved;
}
