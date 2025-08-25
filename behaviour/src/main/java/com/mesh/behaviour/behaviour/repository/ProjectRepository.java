package com.mesh.behaviour.behaviour.repository;

import com.mesh.behaviour.behaviour.model.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    // exact (kept for compatibility)
    Optional<Project> findByUsernameAndProjectName(String username, String projectName);

    // robust: trim/case-insensitive on both sides
    @Query("""
           select p from Project p
           where lower(p.username) = lower(:username)
             and lower(p.projectName) = lower(:projectName)
           """)
    Optional<Project> findByUserAndProjectCI(@Param("username") String username,
                                             @Param("projectName") String projectName);
}
