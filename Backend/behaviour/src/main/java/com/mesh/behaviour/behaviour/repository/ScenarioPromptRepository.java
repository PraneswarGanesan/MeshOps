package com.mesh.behaviour.behaviour.repository;

import com.mesh.behaviour.behaviour.model.ScenarioPrompt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ScenarioPromptRepository extends JpaRepository<ScenarioPrompt, Long> {

    List<ScenarioPrompt> findByUsernameAndProjectNameOrderByCreatedAtDesc(String username, String projectName);

    List<ScenarioPrompt> findByUsernameAndProjectNameAndVersionLabelOrderByCreatedAtDesc(
            String username, String projectName, String versionLabel
    );

    void deleteByUsernameAndProjectNameAndVersionLabel(
            String username, String projectName, String versionLabel
    );
}
