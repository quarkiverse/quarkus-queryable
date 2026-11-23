package io.quarkiverse.queryable.example.model;

import jakarta.persistence.Entity;
import jakarta.persistence.MappedSuperclass;

import io.quarkiverse.queryable.runtime.Q;
import io.quarkiverse.queryable.runtime.QRs;
import io.quarkus.hibernate.orm.panache.PanacheEntity;

@Entity
@QRs("/api/inherited-filters")
public class InheritedClassFilterSample extends FilterSampleBase {
    public String childValue;
}

@MappedSuperclass
@Q
abstract class FilterSampleBase extends PanacheEntity {
    public String inheritedValue;
}
