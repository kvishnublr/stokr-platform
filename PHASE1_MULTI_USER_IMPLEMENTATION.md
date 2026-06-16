# Phase 1 Implementation Guide
## Multi-User Multi-Strategy Platform - Foundation

**Estimated Duration:** 4 weeks  
**Goal:** Establish multi-tenant foundation with RBAC

---

## 📋 PHASE 1 DELIVERABLES

| Deliverable | Description | Status |
|-------------|-------------|--------|
| Organization Module | Organization entity, repository, service, API | 🔴 TODO |
| Enhanced User Module | Multi-user with roles, API keys | 🔴 TODO |
| RBAC System | Permission enum, policy service, middleware | 🔴 TODO |
| Organization Repository | Data access layer | 🔴 TODO |
| Admin API Endpoints | CRUD for orgs and users | 🔴 TODO |
| UI Shell Updates | Multi-user aware navigation | 🔴 TODO |

---

## 🗄️ STEP 1: Database Migrations

### Migration 1: Organizations Table

**File:** `stokr-common/src/main/resources/db/migration/V100__organizations.sql`

```sql
-- Organizations table for multi-tenancy
CREATE TABLE organizations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    slug VARCHAR(100) UNIQUE NOT NULL,
    plan_type VARCHAR(50) DEFAULT 'FREE' NOT NULL,
    max_users INTEGER DEFAULT 1,
    max_strategies INTEGER DEFAULT 5,
    max_brokers INTEGER DEFAULT 1,
    billing_email VARCHAR(255),
    stripe_customer_id VARCHAR(255),
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- Add organization reference to existing tables
ALTER TABLE users ADD COLUMN organization_id UUID REFERENCES organizations(id);
ALTER TABLE broker_accounts ADD COLUMN organization_id UUID REFERENCES organizations(id);

-- Create indexes for performance
CREATE INDEX idx_users_organization ON users(organization_id);
CREATE INDEX idx_broker_accounts_organization ON broker_accounts(organization_id);
```

### Migration 2: Enhanced User Roles

**File:** `stokr-common/src/main/resources/db/migration/V101__user_roles.sql`

```sql
-- User roles for RBAC
CREATE TABLE user_roles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_name VARCHAR(50) NOT NULL,
    permissions JSONB DEFAULT '[]',
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    UNIQUE(user_id, role_name)
);

-- User API keys
CREATE TABLE user_api_keys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    key_hash VARCHAR(255) NOT NULL,
    key_prefix VARCHAR(10) NOT NULL,
    name VARCHAR(100),
    permissions JSONB DEFAULT '[]',
    expires_at TIMESTAMP,
    last_used_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW()
);

-- Organization invitations
CREATE TABLE organization_invitations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    email VARCHAR(255) NOT NULL,
    role VARCHAR(50) DEFAULT 'TRADER',
    token VARCHAR(255) NOT NULL,
    invited_by UUID REFERENCES users(id),
    expires_at TIMESTAMP NOT NULL,
    accepted_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_user_roles_user ON user_roles(user_id);
CREATE INDEX idx_user_api_keys_user ON user_api_keys(user_id);
CREATE INDEX idx_org_invitations_token ON organization_invitations(token);
CREATE INDEX idx_org_invitations_email ON organization_invitations(email);
```

### Migration 3: Strategy Organization Links

**File:** `stokr-common/src/main/resources/db/migration/V102__strategy_organization_links.sql`

```sql
-- Add organization_id to strategy tables
ALTER TABLE strategy_definitions ADD COLUMN organization_id UUID REFERENCES organizations(id);
ALTER TABLE strategy_instances ADD COLUMN organization_id UUID REFERENCES organizations(id);

-- Create indexes
CREATE INDEX idx_strategy_definitions_org ON strategy_definitions(organization_id);
CREATE INDEX idx_strategy_instances_org ON strategy_instances(organization_id);

-- Portfolio allocations (new table)
CREATE TABLE portfolio_allocations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    user_id UUID NOT NULL REFERENCES users(id),
    name VARCHAR(255) NOT NULL,
    total_capital DECIMAL(18,2) NOT NULL,
    available_capital DECIMAL(18,2) NOT NULL,
    reserved_capital DECIMAL(18,2) DEFAULT 0,
    currency VARCHAR(10) DEFAULT 'INR',
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- Allocation rules
CREATE TABLE allocation_rules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    portfolio_id UUID NOT NULL REFERENCES portfolio_allocations(id) ON DELETE CASCADE,
    strategy_definition_id UUID REFERENCES strategy_definitions(id),
    min_allocation DECIMAL(18,2),
    max_allocation DECIMAL(18,2),
    max_positions INTEGER DEFAULT 10,
    priority INTEGER DEFAULT 1,
    auto_rebalance BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_portfolio_allocations_org ON portfolio_allocations(organization_id);
CREATE INDEX idx_portfolio_allocations_user ON portfolio_allocations(user_id);
```

---

## 🔧 STEP 2: Create Organization Module

### Module Structure

```
stokr-organization/
├── pom.xml
├── src/main/java/com/stokr/organization/
│   ├── config/
│   │   └── OrganizationModuleConfig.java
│   ├── domain/
│   │   ├── Organization.java
│   │   ├── OrganizationInvitation.java
│   │   └── PlanType.java
│   ├── repository/
│   │   ├── OrganizationRepository.java
│   │   └── OrganizationInvitationRepository.java
│   ├── service/
│   │   ├── OrganizationService.java
│   │   └── OrganizationInvitationService.java
│   └── web/
│       ├── OrganizationController.java
│       └── InvitationController.java
└── src/main/resources/
    └── application.properties
```

### Domain: Organization.java

**Path:** `stokr-organization/src/main/java/com/stokr/organization/domain/Organization.java`

```java
package com.stokr.organization.domain;

import com.stokr.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity
@Table(name = "organizations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Organization extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Column(unique = true, nullable = false)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_type", nullable = false)
    @Builder.Default
    private PlanType planType = PlanType.FREE;

    @Column(name = "max_users")
    @Builder.Default
    private Integer maxUsers = 1;

    @Column(name = "max_strategies")
    @Builder.Default
    private Integer maxStrategies = 5;

    @Column(name = "max_brokers")
    @Builder.Default
    private Integer maxBrokers = 1;

    @Column(name = "billing_email")
    private String billingEmail;

    @Column(name = "stripe_customer_id")
    private String stripeCustomerId;

    public boolean canAddUser(int currentCount) {
        return currentCount < maxUsers;
    }

    public boolean canAddStrategy(int currentCount) {
        return currentCount < maxStrategies;
    }

    public boolean canAddBroker(int currentCount) {
        return currentCount < maxBrokers;
    }

    public boolean isPremium() {
        return planType != PlanType.FREE;
    }
}
```

### Domain: PlanType.java

**Path:** `stokr-organization/src/main/java/com/stokr/organization/domain/PlanType.java`

```java
package com.stokr.organization.domain;

public enum PlanType {
    FREE("Free", 1, 5, 1),
    STARTER("Starter", 3, 20, 2),
    PRO("Professional", 10, 100, 5),
    ENTERPRISE("Enterprise", 50, -1, 10); // -1 = unlimited

    private final String displayName;
    private final int maxUsers;
    private final int maxStrategies;
    private final int maxBrokers;

    PlanType(String displayName, int maxUsers, int maxStrategies, int maxBrokers) {
        this.displayName = displayName;
        this.maxUsers = maxUsers;
        this.maxStrategies = maxStrategies;
        this.maxBrokers = maxBrokers;
    }

    public String getDisplayName() { return displayName; }
    public int getMaxUsers() { return maxUsers; }
    public int getMaxStrategies() { return maxStrategies; }
    public int getMaxBrokers() { return maxBrokers; }
    public boolean hasUnlimitedStrategies() { return maxStrategies == -1; }
}
```

### Repository: OrganizationRepository.java

**Path:** `stokr-organization/src/main/java/com/stokr/organization/repository/OrganizationRepository.java`

```java
package com.stokr.organization.repository;

import com.stokr.organization.domain.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    Optional<Organization> findBySlug(String slug);

    boolean existsBySlug(String slug);

    @Query("SELECT COUNT(u) FROM users u WHERE u.organization.id = :orgId AND u.deleted = false")
    int countActiveUsers(UUID orgId);

    @Query("SELECT COUNT(s) FROM strategy_definitions s WHERE s.organization.id = :orgId AND s.deleted = false")
    int countStrategies(UUID orgId);

    @Query("SELECT COUNT(b) FROM broker_accounts b WHERE b.organization.id = :orgId AND b.deleted = false")
    int countBrokers(UUID orgId);
}
```

### Service: OrganizationService.java

**Path:** `stokr-organization/src/main/java/com/stokr/organization/service/OrganizationService.java`

```java
package com.stokr.organization.service;

import com.stokr.common.exception.BadRequestException;
import com.stokr.common.exception.NotFoundException;
import com.stokr.organization.domain.Organization;
import com.stokr.organization.domain.PlanType;
import com.stokr.organization.dto.*;
import com.stokr.organization.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrganizationService {

    private final OrganizationRepository organizationRepository;

    @Transactional(readOnly = true)
    public Organization getById(UUID id) {
        return organizationRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Organization not found"));
    }

    @Transactional(readOnly = true)
    public Organization getBySlug(String slug) {
        return organizationRepository.findBySlug(slug)
                .orElseThrow(() -> new NotFoundException("Organization not found"));
    }

    @Transactional
    public OrganizationDto create(CreateOrganizationRequest request) {
        if (organizationRepository.existsBySlug(request.getSlug())) {
            throw new BadRequestException("Organization slug already exists");
        }

        Organization org = Organization.builder()
                .name(request.getName())
                .slug(request.getSlug())
                .planType(request.getPlanType() != null ? request.getPlanType() : PlanType.FREE)
                .maxUsers(request.getPlanType() != null ? request.getPlanType().getMaxUsers() : 1)
                .maxStrategies(request.getPlanType() != null ? request.getPlanType().getMaxStrategies() : 5)
                .maxBrokers(request.getPlanType() != null ? request.getPlanType().getMaxBrokers() : 1)
                .billingEmail(request.getBillingEmail())
                .build();

        Organization saved = organizationRepository.save(org);
        log.info("Created organization: {} ({})", saved.getName(), saved.getId());
        return toDto(saved);
    }

    @Transactional
    public OrganizationDto update(UUID id, UpdateOrganizationRequest request) {
        Organization org = getById(id);

        if (request.getName() != null) {
            org.setName(request.getName());
        }
        if (request.getBillingEmail() != null) {
            org.setBillingEmail(request.getBillingEmail());
        }

        Organization saved = organizationRepository.save(org);
        return toDto(saved);
    }

    @Transactional
    public void delete(UUID id) {
        Organization org = getById(id);
        org.setDeleted(true);
        organizationRepository.save(org);
        log.info("Deleted organization: {}", id);
    }

    @Transactional(readOnly = true)
    public OrganizationUsageDto getUsage(UUID id) {
        Organization org = getById(id);
        int userCount = organizationRepository.countActiveUsers(id);
        int strategyCount = organizationRepository.countStrategies(id);
        int brokerCount = organizationRepository.countBrokers(id);

        return OrganizationUsageDto.builder()
                .organizationId(id)
                .planType(org.getPlanType())
                .userCount(userCount)
                .strategyCount(strategyCount)
                .brokerCount(brokerCount)
                .maxUsers(org.getMaxUsers())
                .maxStrategies(org.getMaxStrategies())
                .maxBrokers(org.getMaxBrokers())
                .canAddUser(org.canAddUser(userCount))
                .canAddStrategy(org.canAddStrategy(strategyCount))
                .canAddBroker(org.canAddBroker(brokerCount))
                .build();
    }

    private OrganizationDto toDto(Organization org) {
        return OrganizationDto.builder()
                .id(org.getId())
                .name(org.getName())
                .slug(org.getSlug())
                .planType(org.getPlanType())
                .maxUsers(org.getMaxUsers())
                .maxStrategies(org.getMaxStrategies())
                .maxBrokers(org.getMaxBrokers())
                .billingEmail(org.getBillingEmail())
                .createdAt(org.getCreatedAt())
                .updatedAt(org.getUpdatedAt())
                .build();
    }
}
```

### Controller: OrganizationController.java

**Path:** `stokr-organization/src/main/java/com/stokr/organization/web/OrganizationController.java`

```java
package com.stokr.organization.web;

import com.stokr.organization.dto.*;
import com.stokr.organization.service.OrganizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations")
@RequiredArgsConstructor
public class OrganizationController {

    private final OrganizationService organizationService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<OrganizationDto> create(
            @RequestBody CreateOrganizationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(organizationService.create(request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrganizationDto> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(organizationService.getById(id));
    }

    @GetMapping("/slug/{slug}")
    public ResponseEntity<OrganizationDto> getBySlug(@PathVariable String slug) {
        return ResponseEntity.ok(organizationService.getBySlug(slug));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('ORG_ADMIN')")
    public ResponseEntity<OrganizationDto> update(
            @PathVariable UUID id,
            @RequestBody UpdateOrganizationRequest request) {
        return ResponseEntity.ok(organizationService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        organizationService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/usage")
    public ResponseEntity<OrganizationUsageDto> getUsage(@PathVariable UUID id) {
        return ResponseEntity.ok(organizationService.getUsage(id));
    }
}
```

---

## 👥 STEP 3: Enhanced User Management

### New: UserRole Entity

**Path:** `stokr-user/src/main/java/com/stokr/user/domain/UserRole.java`

```java
package com.stokr.user.domain;

import com.stokr.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "user_roles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserRole extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private UserProfile user;

    @Column(name = "role_name", nullable = false, length = 50)
    private String roleName;

    @Column(columnDefinition = "jsonb")
    private String permissions;

    public enum Role {
        ADMIN("System Administrator", 0),
        ORG_ADMIN("Organization Admin", 1),
        MANAGER("Manager", 2),
        TRADER("Trader", 3),
        VIEWER("Viewer", 4),
        API_USER("API User", 5);

        private final String displayName;
        private final int level;

        Role(String displayName, int level) {
            this.displayName = displayName;
            this.level = level;
        }

        public String getDisplayName() { return displayName; }
        public int getLevel() { return level; }
        public boolean canManage(Role other) { return this.level <= other.level; }
    }
}
```

### New: UserApiKey Entity

**Path:** `stokr-user/src/main/java/com/stokr/user/domain/UserApiKey.java`

```java
package com.stokr.user.domain;

import com.stokr.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_api_keys")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserApiKey extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private UserProfile user;

    @Column(name = "key_hash", nullable = false)
    private String keyHash;

    @Column(name = "key_prefix", nullable = false, length = 10)
    private String keyPrefix;

    @Column(length = 100)
    private String name;

    @Column(columnDefinition = "jsonb")
    private String permissions;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    public boolean isActive() {
        return !isExpired() && !isDeleted();
    }
}
```

### Service: UserRoleService.java

**Path:** `stokr-user/src/main/java/com/stokr/user/service/UserRoleService.java`

```java
package com.stokr.user.service;

import com.stokr.user.domain.UserRole;
import com.stokr.user.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserRoleService {

    private final UserRoleRepository userRoleRepository;

    private static final Map<String, List<String>> ROLE_PERMISSIONS = Map.of(
        "ADMIN", List.of("*"),
        "ORG_ADMIN", List.of(
            "USER_CREATE", "USER_READ", "USER_UPDATE",
            "STRATEGY_CREATE", "STRATEGY_READ", "STRATEGY_UPDATE", "STRATEGY_DELETE",
            "STRATEGY_START", "STRATEGY_STOP", "STRATEGY_PAUSE",
            "BROKER_CONNECT", "BROKER_READ", "BROKER_UPDATE",
            "PORTFOLIO_VIEW", "PORTFOLIO_ALLOCATE", "PORTFOLIO_REBALANCE",
            "MARKETPLACE_VIEW", "MARKETPLACE_PUBLISH",
            "AUDIT_VIEW"
        ),
        "MANAGER", List.of(
            "STRATEGY_CREATE", "STRATEGY_READ", "STRATEGY_UPDATE",
            "STRATEGY_START", "STRATEGY_STOP",
            "BROKER_READ",
            "PORTFOLIO_VIEW", "PORTFOLIO_ALLOCATE",
            "MARKETPLACE_VIEW"
        ),
        "TRADER", List.of(
            "STRATEGY_READ",
            "STRATEGY_START", "STRATEGY_STOP",
            "BROKER_READ",
            "PORTFOLIO_VIEW",
            "MARKETPLACE_VIEW", "MARKETPLACE_SUBSCRIBE"
        ),
        "VIEWER", List.of(
            "STRATEGY_READ",
            "BROKER_READ",
            "PORTFOLIO_VIEW",
            "MARKETPLACE_VIEW"
        ),
        "API_USER", List.of(
            "STRATEGY_READ",
            "STRATEGY_START", "STRATEGY_STOP",
            "PORTFOLIO_VIEW"
        )
    );

    @Transactional
    public UserRole assignRole(UUID userId, UserRole.Role role) {
        UserRole userRole = UserRole.builder()
                .roleName(role.name())
                .permissions(serializePermissions(ROLE_PERMISSIONS.getOrDefault(role.name(), List.of())))
                .build();
        return userRoleRepository.save(userRole);
    }

    @Transactional(readOnly = true)
    public List<String> getUserPermissions(UUID userId) {
        List<UserRole> roles = userRoleRepository.findByUserId(userId);
        Set<String> permissions = new HashSet<>();
        for (UserRole role : roles) {
            List<String> rolePerms = deserializePermissions(role.getPermissions());
            if (rolePerms.contains("*")) {
                return List.of("*"); // Admin has all permissions
            }
            permissions.addAll(rolePerms);
        }
        return new ArrayList<>(permissions);
    }

    @Transactional(readOnly = true)
    public boolean hasPermission(UUID userId, String permission) {
        List<String> permissions = getUserPermissions(userId);
        return permissions.contains("*") || permissions.contains(permission);
    }

    @Transactional(readOnly = true)
    public boolean hasRole(UUID userId, UserRole.Role role) {
        return userRoleRepository.existsByUserIdAndRoleName(userId, role.name());
    }

    @Transactional
    public void removeRole(UUID userId, UserRole.Role role) {
        userRoleRepository.deleteByUserIdAndRoleName(userId, role.name());
    }

    @Transactional
    public void setRoles(UUID userId, List<UserRole.Role> roles) {
        userRoleRepository.deleteByUserId(userId);
        for (UserRole.Role role : roles) {
            assignRole(userId, role);
        }
    }

    private String serializePermissions(List<String> permissions) {
        return String.join(",", permissions);
    }

    private List<String> deserializePermissions(String serialized) {
        if (serialized == null || serialized.isEmpty()) {
            return List.of();
        }
        return Arrays.asList(serialized.split(","));
    }
}
```

---

## 🔐 STEP 4: RBAC Middleware

### Security: Permission Enum

**Path:** `stokr-auth/src/main/java/com/stokr/auth/security/Permission.java`

```java
package com.stokr.auth.security;

public enum Permission {
    // User Management
    USER_CREATE,
    USER_READ,
    USER_UPDATE,
    USER_DELETE,
    USER_ROLE_ASSIGN,

    // Strategy Management
    STRATEGY_CREATE,
    STRATEGY_READ,
    STRATEGY_UPDATE,
    STRATEGY_DELETE,
    STRATEGY_START,
    STRATEGY_STOP,
    STRATEGY_PAUSE,
    STRATEGY_CODE_VIEW,
    STRATEGY_CODE_EDIT,

    // Broker Management
    BROKER_CONNECT,
    BROKER_DISCONNECT,
    BROKER_READ,
    BROKER_UPDATE,

    // Portfolio
    PORTFOLIO_VIEW,
    PORTFOLIO_ALLOCATE,
    PORTFOLIO_REBALANCE,

    // Marketplace
    MARKETPLACE_VIEW,
    MARKETPLACE_PUBLISH,
    MARKETPLACE_SUBSCRIBE,

    // Copy Trading
    COPY_TRADING_ENABLE,
    COPY_TRADING_FOLLOW,

    // Admin
    AUDIT_VIEW,
    SYSTEM_CONFIG,
    BILLING_MANAGE
}
```

### Security: PermissionService

**Path:** `stokr-auth/src/main/java/com/stokr/auth/security/PermissionService.java`

```java
package com.stokr.auth.security;

import com.stokr.user.service.UserRoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PermissionService {

    private final UserRoleService userRoleService;

    public boolean hasPermission(String permission) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }

        // Admin always has permission
        if (auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))) {
            return true;
        }

        UUID userId = getCurrentUserId();
        if (userId == null) {
            return false;
        }

        return userRoleService.hasPermission(userId, permission);
    }

    public boolean hasAnyPermission(String... permissions) {
        for (String permission : permissions) {
            if (hasPermission(permission)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasAllPermissions(String... permissions) {
        for (String permission : permissions) {
            if (!hasPermission(permission)) {
                return false;
            }
        }
        return true;
    }

    public List<String> getCurrentUserPermissions() {
        UUID userId = getCurrentUserId();
        if (userId == null) {
            return List.of();
        }
        return userRoleService.getUserPermissions(userId);
    }

    private UUID getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UUID) {
            return (UUID) auth.getPrincipal();
        }
        return null;
    }
}
```

### Security: Organization Access Control

**Path:** `stokr-auth/src/main/java/com/stokr/auth/security/OrganizationSecurityService.java`

```java
package com.stokr.auth.security;

import com.stokr.auth.domain.AuthUser;
import com.stokr.auth.repository.AuthUserRepository;
import com.stokr.organization.domain.Organization;
import com.stokr.organization.service.OrganizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrganizationSecurityService {

    private final OrganizationService organizationService;
    private final AuthUserRepository authUserRepository;
    private final PermissionService permissionService;

    public Organization getCurrentUserOrganization() {
        UUID userId = getCurrentUserId();
        AuthUser user = authUserRepository.findById(userId)
                .orElseThrow(() -> new AccessDeniedException("User not found"));
        
        if (user.getOrganizationId() == null) {
            throw new AccessDeniedException("User does not belong to any organization");
        }
        
        return organizationService.getById(user.getOrganizationId());
    }

    public UUID getCurrentOrganizationId() {
        return getCurrentUserOrganization().getId();
    }

    public void assertOrganizationAccess(UUID organizationId) {
        UUID currentOrgId = getCurrentOrganizationId();
        if (!currentOrgId.equals(organizationId)) {
            log.warn("Organization access denied: user org={}, requested org={}", 
                    currentOrgId, organizationId);
            throw new AccessDeniedException("Access denied to this organization");
        }
    }

    public boolean canCreateStrategy() {
        Organization org = getCurrentUserOrganization();
        int currentCount = organizationService.getUsage(org.getId()).getStrategyCount();
        return org.canAddStrategy(currentCount);
    }

    public boolean canAddUser() {
        Organization org = getCurrentUserOrganization();
        int currentCount = organizationService.getUsage(org.getId()).getUserCount();
        return org.canAddUser(currentCount);
    }

    public boolean canConnectBroker() {
        Organization org = getCurrentUserOrganization();
        int currentCount = organizationService.getUsage(org.getId()).getBrokerCount();
        return org.canAddBroker(currentCount);
    }

    public boolean canUseLiveExecution() {
        Organization org = getCurrentUserOrganization();
        return org.isPremium() && permissionService.hasPermission(Permission.STRATEGY_START.name());
    }

    private UUID getCurrentUserId() {
        return UUID.fromString(
            org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getName()
        );
    }
}
```

---

## 🎨 STEP 5: Frontend Updates

### New: Organization Store

**Path:** `stokr-ui/src/state/organizationStore.ts`

```typescript
import { create } from 'zustand';
import { api } from '@/services/api/client';

interface Organization {
  id: string;
  name: string;
  slug: string;
  planType: 'FREE' | 'STARTER' | 'PRO' | 'ENTERPRISE';
  maxUsers: number;
  maxStrategies: number;
  maxBrokers: number;
}

interface OrganizationUsage {
  organizationId: string;
  planType: string;
  userCount: number;
  strategyCount: number;
  brokerCount: number;
  maxUsers: number;
  maxStrategies: number;
  maxBrokers: number;
  canAddUser: boolean;
  canAddStrategy: boolean;
  canAddBroker: boolean;
}

interface OrganizationState {
  currentOrg: Organization | null;
  usage: OrganizationUsage | null;
  loading: boolean;
  error: string | null;
  
  // Actions
  fetchCurrentOrg: () => Promise<void>;
  fetchUsage: () => Promise<void>;
  canAddStrategy: () => boolean;
  canAddUser: () => boolean;
  canAddBroker: () => boolean;
}

export const useOrganizationStore = create<OrganizationState>((set, get) => ({
  currentOrg: null,
  usage: null,
  loading: false,
  error: null,

  fetchCurrentOrg: async () => {
    set({ loading: true, error: null });
    try {
      const response = await api.get<Organization>('/api/v1/organizations/me');
      set({ currentOrg: response.data, loading: false });
    } catch (error) {
      set({ error: 'Failed to fetch organization', loading: false });
    }
  },

  fetchUsage: async () => {
    const org = get().currentOrg;
    if (!org) return;
    
    try {
      const response = await api.get<OrganizationUsage>(
        `/api/v1/organizations/${org.id}/usage`
      );
      set({ usage: response.data });
    } catch (error) {
      console.error('Failed to fetch organization usage:', error);
    }
  },

  canAddStrategy: () => {
    const usage = get().usage;
    return usage?.canAddStrategy ?? false;
  },

  canAddUser: () => {
    const usage = get().usage;
    return usage?.canAddUser ?? false;
  },

  canAddBroker: () => {
    const usage = get().usage;
    return usage?.canAddBroker ?? false;
  },
}));
```

### New: Permission Hook

**Path:** `stokr-ui/src/hooks/usePermission.ts`

```typescript
import { useAuthStore } from '@/state/session';
import { Permission } from '@/types/auth';

const ROLE_PERMISSIONS: Record<string, Permission[]> = {
  ADMIN: ['*'],
  ORG_ADMIN: [
    'USER_CREATE', 'USER_READ', 'USER_UPDATE',
    'STRATEGY_CREATE', 'STRATEGY_READ', 'STRATEGY_UPDATE', 'STRATEGY_DELETE',
    'STRATEGY_START', 'STRATEGY_STOP', 'STRATEGY_PAUSE',
    'BROKER_CONNECT', 'BROKER_READ', 'BROKER_UPDATE',
    'PORTFOLIO_VIEW', 'PORTFOLIO_ALLOCATE', 'PORTFOLIO_REBALANCE',
    'MARKETPLACE_VIEW', 'MARKETPLACE_PUBLISH',
    'AUDIT_VIEW'
  ],
  MANAGER: [
    'STRATEGY_CREATE', 'STRATEGY_READ', 'STRATEGY_UPDATE',
    'STRATEGY_START', 'STRATEGY_STOP',
    'BROKER_READ',
    'PORTFOLIO_VIEW', 'PORTFOLIO_ALLOCATE',
    'MARKETPLACE_VIEW'
  ],
  TRADER: [
    'STRATEGY_READ',
    'STRATEGY_START', 'STRATEGY_STOP',
    'BROKER_READ',
    'PORTFOLIO_VIEW',
    'MARKETPLACE_VIEW', 'MARKETPLACE_SUBSCRIBE'
  ],
  VIEWER: [
    'STRATEGY_READ',
    'BROKER_READ',
    'PORTFOLIO_VIEW',
    'MARKETPLACE_VIEW'
  ],
};

export function usePermission() {
  const { user } = useAuthStore();

  const hasPermission = (permission: Permission): boolean => {
    if (!user?.roles) return false;
    
    for (const role of user.roles) {
      const perms = ROLE_PERMISSIONS[role];
      if (!perms) continue;
      if (perms.includes('*')) return true;
      if (perms.includes(permission)) return true;
    }
    return false;
  };

  const hasAnyPermission = (...permissions: Permission[]): boolean => {
    return permissions.some(p => hasPermission(p));
  };

  const hasAllPermissions = (...permissions: Permission[]): boolean => {
    return permissions.every(p => hasPermission(p));
  };

  const hasRole = (role: string): boolean => {
    return user?.roles?.includes(role) ?? false;
  };

  const isAdmin = () => hasRole('ADMIN') || hasRole('ORG_ADMIN');
  const isManager = () => hasRole('ADMIN') || hasRole('ORG_ADMIN') || hasRole('MANAGER');
  const isTrader = () => hasRole('TRADER');
  const isViewer = () => hasRole('VIEWER');

  return {
    hasPermission,
    hasAnyPermission,
    hasAllPermissions,
    hasRole,
    isAdmin,
    isManager,
    isTrader,
    isViewer,
  };
}
```

### Permission Gate Component

**Path:** `stokr-ui/src/components/auth/PermissionGate.tsx`

```tsx
import { ReactNode } from 'react';
import { usePermission } from '@/hooks/usePermission';
import { Permission } from '@/types/auth';

interface PermissionGateProps {
  permission?: Permission;
  permissions?: Permission[];
  requireAll?: boolean;
  role?: string;
  fallback?: ReactNode;
  children: ReactNode;
}

export function PermissionGate({
  permission,
  permissions,
  requireAll = false,
  role,
  fallback = null,
  children,
}: PermissionGateProps) {
  const { hasPermission, hasAnyPermission, hasAllPermissions, hasRole } = usePermission();

  let allowed = true;

  if (role) {
    allowed = hasRole(role);
  } else if (permission) {
    allowed = hasPermission(permission);
  } else if (permissions) {
    allowed = requireAll 
      ? hasAllPermissions(...permissions)
      : hasAnyPermission(...permissions);
  }

  return <>{allowed ? children : fallback}</>;
}

// Usage examples:
// <PermissionGate permission="STRATEGY_CREATE">
//   <Button onClick={createStrategy}>New Strategy</Button>
// </PermissionGate>
//
// <PermissionGate role="ADMIN" fallback={<div>Admin only</div>}>
//   <AdminPanel />
// </PermissionGate>
//
// <PermissionGate 
//   permissions={['STRATEGY_START', 'STRATEGY_STOP']}
//   requireAll
// >
//   <ControlPanel />
// </PermissionGate>
```

---

## 📋 IMPLEMENTATION CHECKLIST

### Week 1: Database & Organization Module
- [ ] Create V100, V101, V102 migrations
- [ ] Create stokr-organization module
- [ ] Implement Organization domain
- [ ] Implement OrganizationRepository
- [ ] Implement OrganizationService
- [ ] Implement OrganizationController
- [ ] Test organization CRUD

### Week 2: User Enhancement
- [ ] Add organization_id to AuthUser
- [ ] Create UserRole entity
- [ ] Create UserApiKey entity
- [ ] Implement UserRoleService
- [ ] Implement ApiKeyService
- [ ] Add invitation system
- [ ] Test user-role assignment

### Week 3: Security
- [ ] Create Permission enum
- [ ] Implement PermissionService
- [ ] Implement OrganizationSecurityService
- [ ] Add @PreAuthorize annotations
- [ ] Add organization filter to queries
- [ ] Test RBAC enforcement

### Week 4: Frontend Integration
- [ ] Create organizationStore
- [ ] Create usePermission hook
- [ ] Create PermissionGate component
- [ ] Update AppShell for multi-user
- [ ] Add org switcher (for admins)
- [ ] Add user menu with roles
- [ ] Test permission-based rendering

---

## 🧪 TESTING STRATEGY

### Unit Tests
```java
@Test
void shouldAssignRole() {
    UUID userId = UUID.randomUUID();
    UserRole role = userRoleService.assignRole(userId, UserRole.Role.TRADER);
    assertEquals("TRADER", role.getRoleName());
}

@Test
void adminHasAllPermissions() {
    List<String> perms = userRoleService.getUserPermissions(adminUserId);
    assertTrue(perms.contains("*"));
}

@Test
void traderHasLimitedPermissions() {
    List<String> perms = userRoleService.getUserPermissions(traderUserId);
    assertTrue(perms.contains("STRATEGY_READ"));
    assertFalse(perms.contains("USER_CREATE"));
}
```

### Integration Tests
```java
@Test
void shouldDenyAccessToOtherOrg() {
    assertThrows(AccessDeniedException.class, () -> {
        orgSecurity.assertOrganizationAccess(otherOrgId);
    });
}

@Test
void shouldAllowAccessToOwnOrg() {
    assertDoesNotThrow(() -> {
        orgSecurity.assertOrganizationAccess(currentOrgId);
    });
}
```

---

## 📈 PHASE 1 SUCCESS CRITERIA

| Criteria | Target | Verification |
|----------|--------|--------------|
| Organization CRUD | 100% | API tests pass |
| User-role assignment | Working | Manual test |
| RBAC enforcement | All endpoints secured | Security audit |
| Query filtering | By org_id | Integration tests |
| Frontend permissions | Correct rendering | UI test |
| Migration rollback | < 5 min | Dry run |

---

## 🚀 NEXT STEPS AFTER PHASE 1

1. **Phase 2**: Enhanced Strategy Management
   - Unlimited strategy instances
   - Strategy cloning
   - Portfolio allocation per strategy

2. **Phase 3**: Multi-Broker Support
   - Broker connection wizard
   - Broker-specific parameters

3. **Phase 4**: Marketplace & Copy Trading
   - Strategy sharing
   - Follow traders

---

**Document Version:** 1.0  
**Last Updated:** 2026-06-16
