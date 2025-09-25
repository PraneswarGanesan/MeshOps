package com.mesh.unittest.repository;

import com.mesh.unittest.model.UnitTestRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UnitTestRunRepository extends JpaRepository<UnitTestRun, Long> {
    
    List<UnitTestRun> findByUsernameAndProjectNameAndVersionOrderByCreatedAtDesc(
            String username, String projectName, String version);
    
    Optional<UnitTestRun> findByUsernameAndProjectNameAndVersionAndId(
            String username, String projectName, String version, Long id);
    
    @Query("SELECT r FROM UnitTestRun r WHERE r.status IN :statuses")
    List<UnitTestRun> findByStatusIn(@Param("statuses") List<UnitTestRun.RunStatus> statuses);
    
    List<UnitTestRun> findByStatus(UnitTestRun.RunStatus status);
}
