package com.mesh.user.UserService.dto;

import jakarta.persistence.Column;
import lombok.Data;

@Data
public class RegisterResponse {
    private String username;
    private String email;
    private  String password;
    private String userType;
    private String premiumType;
}
