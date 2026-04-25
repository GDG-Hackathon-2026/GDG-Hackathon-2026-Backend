package com.moggo._gdg.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor
public class User {

    @Id
    @Column(length = 128)
    private String uid;

    @Column(name = "carbon_used_g", nullable = false)
    private double carbonUsedG;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public User(String uid) {
        this.uid = uid;
        this.carbonUsedG = 0;
        this.createdAt = Instant.now();
    }

    public void addCarbon(double gramsCO2eq) {
        this.carbonUsedG += gramsCO2eq;
    }

    public void resetCarbon() {
        this.carbonUsedG = 0;
    }
}
