package io.quarkiverse.queryable.example.model;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import jakarta.persistence.Transient;

import io.quarkiverse.queryable.runtime.Q;
import io.quarkiverse.queryable.runtime.QLogicalDelete;
import io.quarkiverse.queryable.runtime.QRLike;
import io.quarkiverse.queryable.runtime.QRs;
import io.quarkus.hibernate.orm.panache.PanacheEntity;

@Entity
@Q
@QRs("/api/class-filters")
public class ClassFilterSample extends PanacheEntity {
    public String name;

    @Q(prefix = "exact", name = "label")
    @QRLike(prefix = "starts", name = "label")
    public String title;

    @Temporal(TemporalType.TIMESTAMP)
    public Date createdAt;

    @ElementCollection
    public List<String> tags = new ArrayList<>();

    @Q
    @ElementCollection
    public Set<Integer> ratings = new HashSet<>();

    @QLogicalDelete
    public boolean active = true;

    @Transient
    public String ignored;
}
