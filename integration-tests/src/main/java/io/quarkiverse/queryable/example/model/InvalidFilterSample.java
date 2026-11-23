package io.quarkiverse.queryable.example.model;

import jakarta.persistence.Entity;

import org.hibernate.annotations.Filter;

import io.quarkiverse.queryable.runtime.QFilter;
import io.quarkiverse.queryable.runtime.QOption;
import io.quarkus.hibernate.orm.panache.PanacheEntity;

@Entity
@Filter(name = "Customer.coordinator", condition = "id = :coordinator_id and id = :country")
@QFilter(name = "Customer.coordinator", queryParam = "flag", options = QOption.WITHOUT_PARAMETERS)
public class InvalidFilterSample extends PanacheEntity {
}
