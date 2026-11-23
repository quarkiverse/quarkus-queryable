package io.quarkiverse.queryable.example.model;

import java.util.function.Supplier;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

import io.quarkiverse.queryable.runtime.Q;
import io.quarkiverse.queryable.runtime.QFilter;
import io.quarkiverse.queryable.runtime.QLike;
import io.quarkiverse.queryable.runtime.QOption;
import io.quarkiverse.queryable.runtime.QRs;
import io.quarkus.hibernate.orm.panache.PanacheEntity;

@Entity
@QRs("/api/options")
@FilterDef(name = "OptionSample.current")
@Filter(name = "OptionSample.current", condition = "archived = false")
@QFilter(name = "OptionSample.current", queryParam = "only.current", options = QOption.WITHOUT_PARAMETERS)
@FilterDef(name = "OptionSample.invalid")
@Filter(name = "OptionSample.invalid", condition = "missing_test_column = 1")
@QFilter(name = "OptionSample.invalid", queryParam = "only.invalid", options = QOption.WITHOUT_PARAMETERS)
@FilterDef(name = "OptionSample.resolved", parameters = @ParamDef(name = "archived", type = Boolean.class, resolver = OptionSample.ArchivedResolver.class))
@Filter(name = "OptionSample.resolved", condition = "archived = :archived")
@QFilter(name = "OptionSample.resolved", queryParam = "only.resolved", options = QOption.WITHOUT_PARAMETERS)
public class OptionSample extends PanacheEntity {
    @Q(condition = "IT", options = QOption.EXECUTE_ALWAYS)
    public String country;

    @QLike(condition = "AL", options = QOption.EXECUTE_ALWAYS)
    public String name;

    @Q(condition = "10", options = QOption.EXECUTE_ALWAYS)
    public Integer rating;

    @Q(condition = "true", options = QOption.EXECUTE_ALWAYS)
    public boolean enabled;

    @Enumerated(EnumType.STRING)
    @Q(condition = "OPEN", options = QOption.EXECUTE_ALWAYS)
    public State state;

    @Q
    public boolean archived;

    public enum State {
        OPEN,
        CLOSED
    }

    public static class ArchivedResolver implements Supplier<Boolean> {
        @Override
        public Boolean get() {
            return false;
        }
    }
}
