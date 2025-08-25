package com.mesh.user.UserService.dto;

import lombok.Data;

@Data
public class LoginResponse {
    private String message;
    private String username;
    private String userType;
    private String premiumType;
    private String Token;
    private int otp;
}
