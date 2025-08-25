package com.mesh.behaviour.behaviour.repository;

import com.mesh.behaviour.behaviour.model.Run;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RunRepository extends JpaRepository<Run, Long> {

    List<Run> findByUsernameAndProjectNameOrderByIdDesc(String username, String projectName);

    Optional<Run> findTopByUsernameAndProjectNameOrderByIdDesc(String username, String projectName);

    List<Run> findByUsernameAndProjectNameAndIsDone(String username, String projectName, Boolean isDone);
}
