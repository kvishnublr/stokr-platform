package com.stokr.core.service;

import com.stokr.core.domain.Organization;
import com.stokr.core.domain.User;
import com.stokr.core.dto.*;
import com.stokr.core.repository.OrganizationRepository;
import com.stokr.core.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public List<UserDto> getUsersByOrganization(UUID organizationId) {
        return userRepository.findByOrganizationIdAndDeletedFalse(organizationId)
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public UserDto getUser(UUID userId) {
        User user = userRepository.findByIdAndDeletedFalse(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        return toDto(user);
    }

    @Transactional
    public UserDto createUser(UUID organizationId, CreateUserRequest request, String creatorRole) {
        if (!"ADMIN".equalsIgnoreCase(creatorRole) && !"MANAGER".equalsIgnoreCase(creatorRole)) {
            throw new IllegalArgumentException("Only admins and managers can create users");
        }

        Organization org = organizationRepository.findByIdAndDeletedFalse(organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Organization not found"));

        int currentCount = userRepository.countByOrganizationId(organizationId);
        if (!org.canAddUser(currentCount)) {
            throw new IllegalArgumentException("Organization user limit reached");
        }

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email already registered");
        }

        User user = User.builder()
                .email(request.getEmail().toLowerCase().trim())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .role(request.getRole() != null ? request.getRole() : "TRADER")
                .status("ACTIVE")
                .organizationId(organizationId)
                .emailVerified(false)
                .build();

        User saved = userRepository.save(user);
        log.info("User created: {} in org: {}", saved.getEmail(), organizationId);

        return toDto(saved);
    }

    @Transactional
    public UserDto updateUser(UUID userId, UpdateUserRequest request) {
        User user = userRepository.findByIdAndDeletedFalse(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (request.getFirstName() != null) {
            user.setFirstName(request.getFirstName());
        }
        if (request.getLastName() != null) {
            user.setLastName(request.getLastName());
        }
        if (request.getRole() != null) {
            user.setRole(request.getRole());
        }
        if (request.getStatus() != null) {
            user.setStatus(request.getStatus());
        }
        if (request.getPassword() != null && !request.getPassword().isEmpty()) {
            user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        }

        User saved = userRepository.save(user);
        log.info("User updated: {}", saved.getEmail());

        return toDto(saved);
    }

    @Transactional
    public void deleteUser(UUID userId) {
        User user = userRepository.findByIdAndDeletedFalse(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        user.setDeleted(true);
        userRepository.save(user);
        log.info("User deleted: {}", user.getEmail());
    }

    private UserDto toDto(User user) {
        return UserDto.builder()
                .id(user.getId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .fullName(user.getFullName())
                .role(user.getRole())
                .status(user.getStatus())
                .emailVerified(user.getEmailVerified())
                .organizationId(user.getOrganizationId())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
