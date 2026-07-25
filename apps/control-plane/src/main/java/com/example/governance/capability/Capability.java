package com.example.governance.capability;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "capabilities")
public class Capability {
    @Id
    @Column(nullable = false, updatable = false, length = 128)
    private String id;

    @Column(nullable = false, length = 128)
    private String department;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CapabilityType type;

    protected Capability() {
    }

    public Capability(String id, String department, CapabilityType type) {
        this.id = id;
        this.department = department;
        this.type = type;
    }

    public String id() {
        return id;
    }

    public String department() {
        return department;
    }

    public CapabilityType type() {
        return type;
    }
}
