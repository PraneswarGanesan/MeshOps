package com.mesh.user.UserService.Service;

import com.mesh.user.UserService.Model.User;
import com.mesh.user.UserService.Repository.UserRepository;
import com.mesh.user.UserService.dto.RegisterResponse;
import com.mesh.user.UserService.dto.ResgisterRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AuthenticationService {
    private final UserRepository userRepository;
    private final ExcelService excelService;
    private final PasswordEncoder passwordEncoder;


    public RegisterResponse register(ResgisterRequest request) {
        if (request.getUsername() == null || request.getUsername().isBlank()) {
            throw new IllegalArgumentException("Username cannot be null or empty.");
        }

        if (request.getPassword() == null || request.getPassword().isBlank()) {
            throw new IllegalArgumentException("Password cannot be null or empty.");
        }

        if (request.getEmail() == null || request.getEmail().isBlank()) {
            throw new IllegalArgumentException("Email cannot be null or empty.");
        }
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new RuntimeException("Username already taken");
        }
        String userType = excelService.isAdminUsername(request.getUsername()) ? "admin" : "user";
        String hashedPassword = passwordEncoder.encode(request.getPassword());
        User user = new User();
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());
        user.setPassword(hashedPassword);
        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setUserType(userType);
        user.setPremiumType("free");
        user.setCreatedAT(LocalDateTime.now());
        try{
            userRepository.save(user);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        RegisterResponse response = new RegisterResponse();
        response.setEmail(user.getEmail());
        response.setUsername(user.getEmail());
        response.setPassword(user.getPassword());
        response.setPremiumType(user.getPremiumType());
        response.setUserType(user.getUserType());
        return response;
    }


}
