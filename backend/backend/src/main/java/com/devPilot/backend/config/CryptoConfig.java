package com.devPilot.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;

@Configuration 
public class CryptoConfig {
    @Bean
    TextEncryptor tokenEncryptor(
        @Value("${app.token-encryption-password}") String password,
        @Value("${app.token-encryption-salt}") String salt
    ) {
        return Encryptors.text(password, salt);
    }
}
