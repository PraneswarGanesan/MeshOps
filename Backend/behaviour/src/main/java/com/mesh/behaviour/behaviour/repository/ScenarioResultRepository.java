package com.mesh.behaviour.behaviour.repository;

import com.mesh.behaviour.behaviour.model.ScenarioResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ScenarioResultRepository extends JpaRepository<ScenarioResult, Long> {

    List<ScenarioResult> findByUsernameAndProjectName(String username, String projectName);

    List<ScenarioResult> findByRunId(Long runId);
}
