package com.mesh.user.UserService.Repository;

import com.mesh.user.UserService.Model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.*;
public  interface UserRepository extends JpaRepository<User, String> {

    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);
    void deleteByUsername(String username);
    Optional<User> findByEmail(String email);
}
