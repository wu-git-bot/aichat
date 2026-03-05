package com.example.apichat.auth;

public record LoginResponse(String token, String tokenType, String username, String role) {
}
