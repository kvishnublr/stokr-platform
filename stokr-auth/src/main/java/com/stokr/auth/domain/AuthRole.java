package com.stokr.auth.domain;

import com.stokr.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "auth_roles")
public class AuthRole extends BaseEntity {

    @Column(name = "name", nullable = false, unique = true, length = 64)
    private String name;
}
