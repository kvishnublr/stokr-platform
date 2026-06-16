package com.stokr.admin.spec;

import com.stokr.auth.domain.AuthRole;
import com.stokr.auth.domain.AuthUser;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class AdminUserSpecifications {

    private AdminUserSpecifications() {
    }

    public static Specification<AuthUser> filter(
            String search,
            Boolean enabled,
            String role,
            Instant registeredFrom,
            Instant registeredTo
    ) {
        return (root, query, cb) -> {
            List<Predicate> parts = new ArrayList<>();
            parts.add(cb.isFalse(root.get("deleted")));
            if (enabled != null) {
                parts.add(cb.equal(root.get("enabled"), enabled));
            }
            if (search != null && !search.isBlank()) {
                String like = "%" + search.trim().toLowerCase() + "%";
                Expression<String> email = root.get("email").as(String.class);
                Expression<String> username = root.get("username").as(String.class);
                Expression<String> displayName = root.get("displayName").as(String.class);
                Predicate emailLike = cb.like(cb.lower(email), like);
                Predicate usernameLike = cb.like(cb.lower(username), like);
                Predicate displayNameLike = cb.and(
                        cb.isNotNull(root.get("displayName")),
                        cb.like(cb.lower(displayName), like));
                parts.add(cb.or(emailLike, usernameLike, displayNameLike));
            }
            if (registeredFrom != null) {
                parts.add(cb.greaterThanOrEqualTo(root.get("createdAt"), registeredFrom));
            }
            if (registeredTo != null) {
                parts.add(cb.lessThanOrEqualTo(root.get("createdAt"), registeredTo));
            }
            if (role != null && !role.isBlank()) {
                query.distinct(true);
                Join<AuthUser, AuthRole> roles = root.join("roles", JoinType.INNER);
                parts.add(cb.equal(roles.get("name"), normalizeRole(role)));
            }
            return cb.and(parts.toArray(Predicate[]::new));
        };
    }

    private static String normalizeRole(String role) {
        String r = role.trim();
        if (!r.startsWith("ROLE_")) {
            r = "ROLE_" + r;
        }
        return r.toUpperCase();
    }
}
