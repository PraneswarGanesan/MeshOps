// src/main/java/com/mesh/behaviour/behaviour/repository/UnitTestScenarioPromptRepository.java
package com.mesh.behaviour.behaviour.repository;

import com.mesh.behaviour.behaviour.model.UnitTestScenarioPrompt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UnitTestScenarioPromptRepository extends JpaRepository<UnitTestScenarioPrompt, Long> {
    List<UnitTestScenarioPrompt> findByUsernameAndProjectNameAndVersionLabelOrderByCreatedAtDesc(
            String username, String projectName, String versionLabel);

    void deleteByUsernameAndProjectNameAndVersionLabel(
            String username, String projectName, String versionLabel);
}
