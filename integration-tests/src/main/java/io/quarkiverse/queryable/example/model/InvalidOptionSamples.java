package io.quarkiverse.queryable.example.model;

import jakarta.persistence.Entity;

import io.quarkiverse.queryable.runtime.Q;
import io.quarkiverse.queryable.runtime.QOption;
import io.quarkus.hibernate.orm.panache.PanacheEntity;

public class InvalidOptionSamples {
    @Entity
    public static class MissingCondition extends PanacheEntity {
        @Q(options = QOption.EXECUTE_ALWAYS)
        public String name;
    }

    @Entity
    public static class InvalidBoolean extends PanacheEntity {
        @Q(condition = "invalid", options = QOption.EXECUTE_ALWAYS)
        public boolean enabled;
    }
}
