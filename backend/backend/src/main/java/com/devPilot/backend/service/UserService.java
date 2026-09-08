package com.devPilot.backend.service;

import java.util.UUID;

import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Service;

import com.devPilot.backend.repository.UserRepository;

import jakarta.transaction.Transactional;

import com.devPilot.backend.entity.User;

import lombok.RequiredArgsConstructor;

@Service 
@RequiredArgsConstructor 
public class UserService {
    public final UserRepository userRepository;
    public final TextEncryptor textEncryptor;

    @Transactional(readOnly = true)
    public User requiredById(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found with ID: " + id));
    }

    public String decryptAccessToken(User user) {
        return textEncryptor.decrypt(user.getAccessToken());
    }

    private static Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        } 
        return Long.parseLong(String.valueOf(value));
    }

}
