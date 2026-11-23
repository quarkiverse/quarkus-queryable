package io.quarkiverse.queryable.runtime;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.ws.rs.core.MultivaluedMap;

public final class QueryableFilterContext<T> {
    private final CriteriaBuilder criteriaBuilder;
    private final Root<T> root;
    private final MultivaluedMap<String, String> queryParameters;
    private final List<Predicate> predicates = new ArrayList<>();
    private final List<Predicate> orPredicates = new ArrayList<>();
    private final Set<String> orParameters = new LinkedHashSet<>();

    public QueryableFilterContext(CriteriaBuilder criteriaBuilder, Root<T> root,
            MultivaluedMap<String, String> queryParameters) {
        this.criteriaBuilder = criteriaBuilder;
        this.root = root;
        this.queryParameters = queryParameters;
    }

    public CriteriaBuilder criteriaBuilder() {
        return criteriaBuilder;
    }

    public Root<T> root() {
        return root;
    }

    public String param(String name) {
        return queryParameters.getFirst(name);
    }

    public List<String> params(String name) {
        return queryParameters.getOrDefault(name, List.of());
    }

    public void add(Predicate predicate) {
        predicates.add(predicate);
    }

    public List<String> orParams(String name) {
        String parameter = "or." + name;
        if (queryParameters.containsKey(parameter)) {
            orParameters.add(parameter);
        }
        return List.copyOf(queryParameters.getOrDefault(parameter, List.of()));
    }

    public void addOr(Predicate predicate) {
        orPredicates.add(predicate);
    }

    public List<Predicate> predicates() {
        return List.copyOf(predicates);
    }

    List<Predicate> orPredicates() {
        return List.copyOf(orPredicates);
    }

    Set<String> orParameters() {
        return Set.copyOf(orParameters);
    }
}
