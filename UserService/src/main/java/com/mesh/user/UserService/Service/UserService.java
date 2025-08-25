//package com.mesh.user.UserService.Service;
//
//import com.mesh.user.UserService.Model.User;
//import com.mesh.user.UserService.Repository.UserRepository;
//import jakarta.transaction.Transactional;
//import lombok.RequiredArgsConstructor;
//import org.springframework.dao.DataAccessException;
//import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
//import org.springframework.stereotype.Service;
//
//import java.time.LocalDateTime;
//import java.util.*;
//
//@Service
//@RequiredArgsConstructor
//public class UserService {
//
//    private final UserRepository userRepository;
//    private final BCryptPasswordEncoder passwordEncoder;
//
//
//    // READ by Username
//    public Optional<User> getUserByUsername(String username) {
//        try {
//            return userRepository.findByUsername(username);
//        } catch (DataAccessException e) {
//            throw new RuntimeException("Database error while fetching user: " + e.getMessage(), e);
//        }
//    }
//
//    // READ all
//    public List<User> getAllUsers() {
//        try {
//            List<User> users = userRepository.findAll();
//            if (users.isEmpty()) {
//                throw new IllegalStateException("No users found.");
//            }
//            return users;
//        } catch (DataAccessException e) {
//            throw new RuntimeException("Database error while fetching users: " + e.getMessage(), e);
//        }
//    }
//
//    // UPDATE
//    public boolean updateUser(String id, User updatedUser) {
//        Optional<User> optionalUser = userRepository.findById(id);
//        if (optionalUser.isEmpty()) return false;
//
//        User existingUser = optionalUser.get();
//
//        if (updatedUser.getUsername() != null) existingUser.setUsername(updatedUser.getUsername());
//        if (updatedUser.getEmail() != null) existingUser.setEmail(updatedUser.getEmail());
//        if (updatedUser.getFirstName() != null) existingUser.setFirstName(updatedUser.getFirstName());
//        if (updatedUser.getLastName() != null) existingUser.setLastName(updatedUser.getLastName());
//        if (updatedUser.getUserType() != null) existingUser.setUserType(updatedUser.getUserType());
//        if (updatedUser.getPremiumType() != null) existingUser.setPremiumType(updatedUser.getPremiumType());
//
//        try {
//            userRepository.save(existingUser);
//            return true;
//        } catch (DataAccessException e) {
//            throw new RuntimeException("Database error while updating user: " + e.getMessage(), e);
//        }
//    }
//
//    // DELETE by ID
//    @Transactional
//    public boolean deleteUser(String username) {
//        try {
//            if (!userRepository.existsByUsername(username)) {
//                throw new NoSuchElementException("User not found: " + username);
//            }
//            userRepository.deleteByUsername(username);
//            return true;
//        } catch (DataAccessException e) {
//            throw new RuntimeException("Database error while deleting user: " + e.getMessage(), e);
//        }
//    }
//
//    // DELETE all
//    @Transactional
//    public void deleteAllUsers() {
//        try {
//            if (userRepository.count() == 0) {
//                throw new IllegalStateException("No users to delete.");
//            }
//            userRepository.deleteAll();
//        } catch (DataAccessException e) {
//            throw new RuntimeException("Database error while deleting all users: " + e.getMessage(), e);
//        }
//    }
//
//    // Analytics
//    public Map<String, Object> getUserAnalyticsSummary() {
//        try {
//            List<User> users = userRepository.findAll();
//            if (users.isEmpty()) {
//                throw new IllegalStateException("No users found.");
//            }
//
//            Map<String, Integer> userTypeCount = new HashMap<>();
//            Map<String, Integer> premiumTypeCount = new HashMap<>();
//
//            for (User user : users) {
//                if (user.getUserType() != null) {
//                    userTypeCount.put(user.getUserType(),
//                            userTypeCount.getOrDefault(user.getUserType(), 0) + 1);
//                }
//                if (user.getPremiumType() != null) {
//                    premiumTypeCount.put(user.getPremiumType(),
//                            premiumTypeCount.getOrDefault(user.getPremiumType(), 0) + 1);
//                }
//            }
//
//            Map<String, Object> stats = new HashMap<>();
//            stats.put("totalUsers", users.size());
//            stats.put("userTypeDistribution", userTypeCount);
//            stats.put("premiumTypeDistribution", premiumTypeCount);
//
//            return stats;
//        } catch (DataAccessException e) {
//            throw new RuntimeException("Database error while generating analytics: " + e.getMessage(), e);
//        }
//    }
//}

package com.mesh.user.UserService.Service;

import com.mesh.user.UserService.Model.User;
import com.mesh.user.UserService.Repository.UserRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final S3UserStorageService s3UserStorageService;  // Inject S3 storage service

    // -------- USER CRUD --------

    public Optional<User> getUserByUsername(String username) {
        try {
            return userRepository.findByUsername(username);
        } catch (DataAccessException e) {
            throw new RuntimeException("Database error while fetching user: " + e.getMessage(), e);
        }
    }

    public List<User> getAllUsers() {
        try {
            List<User> users = userRepository.findAll();
            if (users.isEmpty()) {
                throw new IllegalStateException("No users found.");
            }
            return users;
        } catch (DataAccessException e) {
            throw new RuntimeException("Database error while fetching users: " + e.getMessage(), e);
        }
    }

    public boolean updateUser(String id, User updatedUser) {
        Optional<User> optionalUser = userRepository.findById(id);
        if (optionalUser.isEmpty()) return false;

        User existingUser = optionalUser.get();

        if (updatedUser.getUsername() != null) existingUser.setUsername(updatedUser.getUsername());
        if (updatedUser.getEmail() != null) existingUser.setEmail(updatedUser.getEmail());
        if (updatedUser.getFirstName() != null) existingUser.setFirstName(updatedUser.getFirstName());
        if (updatedUser.getLastName() != null) existingUser.setLastName(updatedUser.getLastName());
        if (updatedUser.getUserType() != null) existingUser.setUserType(updatedUser.getUserType());
        if (updatedUser.getPremiumType() != null) existingUser.setPremiumType(updatedUser.getPremiumType());

        try {
            userRepository.save(existingUser);
            return true;
        } catch (DataAccessException e) {
            throw new RuntimeException("Database error while updating user: " + e.getMessage(), e);
        }
    }

    @Transactional
    public boolean deleteUser(String username) {
        try {
            if (!userRepository.existsByUsername(username)) {
                throw new NoSuchElementException("User not found: " + username);
            }
            userRepository.deleteByUsername(username);
            return true;
        } catch (DataAccessException e) {
            throw new RuntimeException("Database error while deleting user: " + e.getMessage(), e);
        }
    }

    @Transactional
    public void deleteAllUsers() {
        try {
            if (userRepository.count() == 0) {
                throw new IllegalStateException("No users to delete.");
            }
            userRepository.deleteAll();
        } catch (DataAccessException e) {
            throw new RuntimeException("Database error while deleting all users: " + e.getMessage(), e);
        }
    }

    // -------- USER ANALYTICS --------

    public Map<String, Object> getUserAnalyticsSummary() {
        try {
            List<User> users = userRepository.findAll();
            if (users.isEmpty()) {
                throw new IllegalStateException("No users found.");
            }

            Map<String, Integer> userTypeCount = new HashMap<>();
            Map<String, Integer> premiumTypeCount = new HashMap<>();

            for (User user : users) {
                if (user.getUserType() != null) {
                    userTypeCount.put(user.getUserType(),
                            userTypeCount.getOrDefault(user.getUserType(), 0) + 1);
                }
                if (user.getPremiumType() != null) {
                    premiumTypeCount.put(user.getPremiumType(),
                            premiumTypeCount.getOrDefault(user.getPremiumType(), 0) + 1);
                }
            }

            Map<String, Object> stats = new HashMap<>();
            stats.put("totalUsers", users.size());
            stats.put("userTypeDistribution", userTypeCount);
            stats.put("premiumTypeDistribution", premiumTypeCount);

            return stats;
        } catch (DataAccessException e) {
            throw new RuntimeException("Database error while generating analytics: " + e.getMessage(), e);
        }
    }

    // -------- PROJECT MANAGEMENT --------

    /**
     * List all projects (folders) for a user in S3.
     */
    public List<String> getUserProjects(String username) {
        return s3UserStorageService.listUserProjects(username);
    }

    /**
     * Create a new project folder for a user in S3.
     */
    public void createProjectForUser(String username, String projectName) {
        s3UserStorageService.createProjectFolder(username, projectName);
        // Optionally add DB project record if you want
    }

    /**
     * Delete a project folder and all files inside for a user.
     */
    public boolean deleteUserProject(String username, String projectName) {
        s3UserStorageService.deleteProject(username, projectName);
        // Optionally remove DB record if exists
        return true;
    }
}

