package org.smartgurukul.chhaon.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request/response payloads for authentication. */
public final class AuthDtos {

    private AuthDtos() {}

    public record SignupRequest(
            @NotBlank String name,
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, max = 100) String password,
            String phone,
            String country,
            String timezone,
            String language) {
    }

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password) {
    }

    public record AuthResponse(
            String token,
            String userId,
            String name,
            String email,
            String plan) {
    }

    // --- Phone-OTP login ---

    public record OtpRequest(
            @NotBlank String phone) {
    }

    public record OtpVerifyRequest(
            @NotBlank String phone,
            @NotBlank String code) {
    }

    /** Returned after a successful verify. The token is a Supabase-compatible access token. */
    public record OtpLoginResponse(
            String token,
            String userId,
            String name,
            String plan,
            boolean isNew,
            // A second, Supabase-compatible token (HS256, SUPABASE_JWT_SECRET, role=authenticated) for the app's direct
            // Supabase calls (reports wallet, medicine photos, feedback pictures). null when not configured.
            String supabaseToken) {
    }
}
