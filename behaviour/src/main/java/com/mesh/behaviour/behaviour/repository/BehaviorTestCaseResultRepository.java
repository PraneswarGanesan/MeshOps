package com.mesh.behaviour.behaviour.repository;

import com.mesh.behaviour.behaviour.model.BehaviorTestCaseResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BehaviorTestCaseResultRepository extends JpaRepository<BehaviorTestCaseResult, Long> {

    List<BehaviorTestCaseResult> findByUsernameAndProjectName(String username, String projectName);

    List<BehaviorTestCaseResult> findByUsernameAndProjectNameAndRunId(String username, String projectName, Long runId);

    void deleteByRunId(Long runId);
}
