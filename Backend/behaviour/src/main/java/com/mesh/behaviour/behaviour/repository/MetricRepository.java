package com.mesh.behaviour.behaviour.repository;

import com.mesh.behaviour.behaviour.model.Metric;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MetricRepository extends JpaRepository<Metric, Long> {

    List<Metric> findByUsernameAndProjectName(String username, String projectName);

    List<Metric> findByUsernameAndProjectNameAndRunId(String username, String projectName, Long runId);

    void deleteByRunId(Long runId);
}
