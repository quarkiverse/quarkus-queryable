package io.quarkiverse.queryable.runtime;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Transient;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.CriteriaSelect;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.PluralAttribute;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import org.jboss.logging.Logger;

import io.quarkiverse.queryable.runtime.spi.QueryableFilterMarker;
import io.quarkiverse.queryable.runtime.spi.QueryableRsListenerMarker;

@ApplicationScoped
public class QueryableEngine {

    private static final Logger LOG = Logger.getLogger(QueryableEngine.class);
    private static final List<String> COMPARISON_PREFIXES = List.of("from", "gt", "efrom", "gte",
            "to", "lt", "eto", "lte");
    private static final List<DateTimeFormatter> LEGACY_DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm:ss", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("dd/MM/uuuu, HH:mm", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("dd/MM/uuuu, HH:mm:ss", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT));

    @Inject
    EntityManager entityManager;

    @Inject
    TransactionSynchronizationRegistry transactionSynchronizationRegistry;

    @Inject
    Instance<QueryableRsListenerMarker> rsListeners;

    @Inject
    Instance<QueryableFilterMarker> filterBeans;

    public <T> Response list(Class<T> entityType, UriInfo uriInfo) {
        MultivaluedMap<String, String> parameters = uriInfo.getQueryParameters();
        int startRow = integer(parameters, "startRow", 0);
        int pageSize = integer(parameters, "pageSize", 10);
        long total;
        List<T> result;
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<T> countRoot = countQuery.from(entityType);
        countQuery.select(cb.count(countRoot));
        List<Predicate> countPredicates = predicates(entityType, cb, countRoot, parameters);
        if (!countPredicates.isEmpty()) {
            countQuery.where(countPredicates);
        }
        try (HibernateFilterScope ignored = HibernateFilterScope.open(entityManager, entityType, parameters)) {
            total = entityManager.createQuery((CriteriaSelect<Long>) countQuery).getSingleResult();
        }

        CriteriaQuery<T> query = cb.createQuery(entityType);
        Root<T> root = query.from(entityType);
        query.select(root);
        List<Predicate> predicates = predicates(entityType, cb, root, parameters);
        if (!predicates.isEmpty()) {
            query.where(predicates);
        }
        applyOrder(cb, query, root, parameters.getFirst("orderBy"), entityType);
        try (HibernateFilterScope ignored = HibernateFilterScope.open(entityManager, entityType, parameters)) {
            var typedQuery = entityManager.createQuery((CriteriaSelect<T>) query);
            if (pageSize > 0) {
                typedQuery.setFirstResult(startRow).setMaxResults(pageSize);
            }
            result = typedQuery.getResultList();
        }
        dispatchBefore(entityType, QueryableOperation.LIST, null, null);
        dispatchAfter(entityType, QueryableOperation.LIST, result);
        return Response.ok(result)
                .header("startRow", startRow)
                .header("pageSize", pageSize)
                .header("listSize", total)
                .header("Access-Control-Expose-Headers", "startRow, pageSize, listSize")
                .build();
    }

    public <T> Response get(Class<T> entityType, String id) {
        Object convertedId = convertId(entityType, id);
        dispatchBefore(entityType, QueryableOperation.FETCH, null, convertedId);
        T entity = entityManager.find(entityType, convertedId);
        if (entity == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        dispatchAfter(entityType, QueryableOperation.FETCH, entity, convertedId);
        return Response.ok(entity).build();
    }

    public <T> Response create(Class<T> entityType, T entity) {
        return Response.ok(createEntity(entityType, entity)).build();
    }

    public <T> T createEntity(Class<T> entityType, T entity) {
        dispatchBefore(entityType, QueryableOperation.CREATE, entity, null);
        T persisted = persist(entity);
        dispatchAfter(entityType, QueryableOperation.CREATE, persisted, identifier(persisted));
        return persisted;
    }

    @Transactional
    public <T> T persist(T entity) {
        entityManager.persist(entity);
        entityManager.flush();
        return entity;
    }

    public <T> Response update(Class<T> entityType, String id, T entity) {
        return Response.ok(updateEntity(entityType, convertId(entityType, id), entity)).build();
    }

    public <T> T updateEntity(Class<T> entityType, Object convertedId, T entity) {
        Object suppliedId = identifier(entity);
        if (suppliedId == null) {
            assignIdentifier(entity, convertedId);
        } else if (!convertedId.equals(suppliedId)) {
            throw new IllegalArgumentException("The entity id does not match the path id");
        }
        dispatchBefore(entityType, QueryableOperation.UPDATE, entity, convertedId);
        T updated = merge(entity);
        dispatchAfter(entityType, QueryableOperation.UPDATE, updated, convertedId);
        return updated;
    }

    @Transactional
    public <T> T merge(T entity) {
        return entityManager.merge(entity);
    }

    public <T> Response delete(Class<T> entityType, String id) {
        boolean removed = deleteEntity(entityType, convertId(entityType, id));
        return removed ? Response.noContent().build() : Response.status(Response.Status.NOT_FOUND).build();
    }

    public <T> boolean deleteEntity(Class<T> entityType, Object convertedId) {
        dispatchBefore(entityType, QueryableOperation.DELETE, null, convertedId);
        if (!remove(entityType, convertedId)) {
            return false;
        }
        dispatchAfter(entityType, QueryableOperation.DELETE, null, convertedId);
        return true;
    }

    public <T> T getEntity(Class<T> entityType, Object id) {
        return entityManager.find(entityType, id);
    }

    public long count(Class<?> entityType) {
        return entityManager.createQuery(
                "select count(e) from " + entityType.getSimpleName() + " e", Long.class).getSingleResult();
    }

    @Transactional
    public boolean remove(Class<?> entityType, Object id) {
        Object entity = entityManager.find(entityType, id);
        if (entity == null) {
            return false;
        }
        entityManager.remove(entity);
        entityManager.flush();
        return true;
    }

    private <T> List<Predicate> predicates(Class<T> entityType, CriteriaBuilder cb, Root<T> root,
            MultivaluedMap<String, String> parameters) {
        List<Predicate> predicates = new ArrayList<>();
        List<Predicate> alternatives = new ArrayList<>();
        Set<String> handledOrParameters = new HashSet<>();
        for (QFilter filter : entityType.getAnnotationsByType(QFilter.class)) {
            rejectOr(parameters, filter.queryParam(), "Hibernate filters cannot be used in OR");
        }
        for (Field field : fields(entityType)) {
            Q equal = field.getAnnotation(Q.class);
            boolean automatic = equal == null
                    && (entityType.isAnnotationPresent(Q.class) || field.getDeclaringClass().isAnnotationPresent(Q.class))
                    && automaticField(field, root);
            if (equal != null || automatic) {
                String name = equal == null || equal.name().isBlank() ? field.getName() : equal.name();
                String prefix = equal == null ? "obj" : equal.prefix();
                String equality = prefix + "." + name;
                boolean collection = field.isAnnotationPresent(ElementCollection.class);
                if (equal != null && fixedCondition(field, equal.options(), equal.condition(), "@Q")) {
                    if (collection) {
                        throw new IllegalStateException("EXECUTE_ALWAYS is not supported on collection field " + field);
                    }
                    rejectOr(parameters, equality, "EXECUTE_ALWAYS cannot be used in OR");
                    rejectOr(parameters, "not." + name, "EXECUTE_ALWAYS cannot be used in OR");
                    for (String operator : COMPARISON_PREFIXES) {
                        rejectOr(parameters, operator + "." + name, "EXECUTE_ALWAYS cannot be used in OR");
                    }
                    predicates.add(cb.equal(path(root, field.getName()), fixedValue(field, equal.condition())));
                } else {
                    addEqualOrRange(field, prefix, name, cb, root, parameters, predicates);
                    List<String> names = new ArrayList<>(List.of(equality, "not." + name));
                    if (!collection) {
                        for (String operator : COMPARISON_PREFIXES) {
                            names.add(operator + "." + name);
                        }
                    }
                    for (String nameToMatch : names) {
                        addOrValues(nameToMatch, parameters, handledOrParameters, alternatives,
                                single -> {
                                    validateOrValue(field, single.getFirst(nameToMatch), false);
                                    addEqualOrRange(field, prefix, name, cb, root, single, alternatives);
                                });
                    }
                }
            }
            QLike like = field.getAnnotation(QLike.class);
            if (like != null) {
                String name = parameterName(like.prefix(), like.name(), field);
                String notLike = parameterName("notlike", like.name(), field);
                String empty = parameterName("ie", like.name(), field);
                String notEmpty = parameterName("nie", like.name(), field);
                if (fixedCondition(field, like.options(), like.condition(), "@QLike")) {
                    rejectOr(parameters, name, "EXECUTE_ALWAYS cannot be used in OR");
                    rejectOr(parameters, notLike, "EXECUTE_ALWAYS cannot be used in OR");
                    rejectOr(parameters, empty, "EXECUTE_ALWAYS cannot be used in OR");
                    rejectOr(parameters, notEmpty, "EXECUTE_ALWAYS cannot be used in OR");
                    addLikeValue(field, like.condition(), cb, root, predicates);
                } else {
                    addLike(field, name, cb, root, parameters, predicates);
                    addOrValues(name, parameters, handledOrParameters, alternatives,
                            single -> addLike(field, name, cb, root, single, alternatives));
                    addNotLike(field, notLike, cb, root, parameters, predicates);
                    addOrValues(notLike, parameters, handledOrParameters, alternatives,
                            single -> addNotLike(field, notLike, cb, root, single, alternatives));
                    addEmpty(field, empty, false, cb, root, parameters, predicates);
                    addOrValues(empty, parameters, handledOrParameters, alternatives,
                            single -> addEmpty(field, empty, false, cb, root, single, alternatives));
                    addEmpty(field, notEmpty, true, cb, root, parameters, predicates);
                    addOrValues(notEmpty, parameters, handledOrParameters, alternatives,
                            single -> addEmpty(field, notEmpty, true, cb, root, single, alternatives));
                }
            }
            QRLike rightLike = field.getAnnotation(QRLike.class);
            if (rightLike != null) {
                String name = parameterName(rightLike.prefix(), rightLike.name(), field);
                addDirectionalLike(field, name, true, cb, root, parameters, predicates);
                addOrValues(name, parameters, handledOrParameters, alternatives,
                        single -> addDirectionalLike(field, name, true, cb, root, single, alternatives));
            }
            QLLike leftLike = field.getAnnotation(QLLike.class);
            if (leftLike != null) {
                String name = parameterName(leftLike.prefix(), leftLike.name(), field);
                addDirectionalLike(field, name, false, cb, root, parameters, predicates);
                addOrValues(name, parameters, handledOrParameters, alternatives,
                        single -> addDirectionalLike(field, name, false, cb, root, single, alternatives));
            }
            QList list = field.getAnnotation(QList.class);
            if (list != null) {
                String name = parameterName(list.prefix(), list.name(), field);
                addList(field, name, cb, root, parameters, predicates);
                addOrValues(name, parameters, handledOrParameters, alternatives,
                        single -> {
                            validateOrValue(field, single.getFirst(name), true);
                            addList(field, name, cb, root, single, alternatives);
                        });
            }
            QLikeList likeList = field.getAnnotation(QLikeList.class);
            if (likeList != null) {
                String name = parameterName(likeList.prefix(), likeList.name(), field);
                addLikeList(field, name, cb, root, parameters, predicates);
                addOrValues(name, parameters, handledOrParameters, alternatives,
                        single -> addLikeList(field, name, cb, root, single, alternatives));
            }
            QNil nil = field.getAnnotation(QNil.class);
            if (nil != null) {
                String name = parameterName(nil.prefix(), nil.name(), field);
                if (parameters.containsKey(name)) {
                    predicates.add(cb.isNotNull(path(root, field.getName())));
                }
                addOrValues(name, parameters, handledOrParameters, alternatives,
                        single -> alternatives.add(cb.isNotNull(path(root, field.getName()))));
            }
            QNotNil notNil = field.getAnnotation(QNotNil.class);
            if (notNil != null) {
                String name = parameterName(notNil.prefix(), notNil.name(), field);
                if (parameters.containsKey(name)) {
                    predicates.add(cb.isNull(path(root, field.getName())));
                }
                addOrValues(name, parameters, handledOrParameters, alternatives,
                        single -> alternatives.add(cb.isNull(path(root, field.getName()))));
            }
            QLogicalDelete logicalDelete = field.getAnnotation(QLogicalDelete.class);
            if (logicalDelete != null) {
                String name = parameterName(logicalDelete.prefix(), logicalDelete.name(), field);
                rejectOr(parameters, name, "Logical-delete filters cannot be used in OR");
                String value = parameters.getFirst(name);
                predicates.add(cb.equal(path(root, field.getName()), value == null || value.isBlank()
                        ? Boolean.TRUE
                        : convert(Boolean.class, value)));
            }
        }
        applyFilters(entityType, cb, root, parameters, predicates, alternatives, handledOrParameters);
        for (String name : parameters.keySet()) {
            if (name.startsWith("or.") && !handledOrParameters.contains(name)) {
                throw new BadRequestException("Unsupported OR query parameter: " + name);
            }
        }
        if (!alternatives.isEmpty()) {
            predicates.add(cb.or(alternatives));
        } else if (parameters.keySet().stream().anyMatch(name -> name.startsWith("or."))) {
            throw new BadRequestException("No predicate was produced for OR query parameters");
        }
        return predicates;
    }

    private static boolean automaticField(Field field, Root<?> root) {
        int modifiers = field.getModifiers();
        if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers) || field.isSynthetic()
                || field.isAnnotationPresent(Transient.class) || field.isAnnotationPresent(QLogicalDelete.class)) {
            return false;
        }
        Attribute<?, ?> attribute = root.getModel().getAttributes().stream()
                .filter(item -> item.getName().equals(field.getName()))
                .findFirst().orElse(null);
        if (attribute == null) {
            return false;
        }
        if (field.isAnnotationPresent(ElementCollection.class)
                && Set.of(Collection.class, List.class, Set.class).contains(field.getType())
                && attribute instanceof PluralAttribute<?, ?, ?> plural) {
            Class<?> type = plural.getElementType().getJavaType();
            return QueryableTypes.isScalar(type.getName(), type.isEnum());
        }
        Class<?> type = field.getType();
        return !attribute.isAssociation() && !attribute.isCollection()
                && QueryableTypes.isScalar(type.getName(), type.isEnum());
    }

    private static void rejectOr(MultivaluedMap<String, String> parameters, String name, String reason) {
        if (parameters.containsKey("or." + name)) {
            throw new BadRequestException(reason + ": or." + name);
        }
    }

    private static void addOrValues(String name, MultivaluedMap<String, String> parameters, Set<String> handled,
            List<Predicate> alternatives, Consumer<MultivaluedMap<String, String>> add) {
        String orName = "or." + name;
        if (!parameters.containsKey(orName)) {
            return;
        }
        List<String> values = parameters.get(orName);
        if (values == null || values.isEmpty()) {
            throw new BadRequestException("Missing value for " + orName);
        }
        for (String value : values) {
            if (value == null) {
                throw new BadRequestException("Missing value for " + orName);
            }
            MultivaluedMap<String, String> single = new MultivaluedHashMap<>();
            single.add(name, value);
            int previous = alternatives.size();
            try {
                add.accept(single);
            } catch (IllegalArgumentException | java.time.DateTimeException exception) {
                throw new BadRequestException("Invalid value for " + orName, exception);
            }
            if (alternatives.size() == previous) {
                throw new BadRequestException("Invalid value for " + orName);
            }
        }
        handled.add(orName);
    }

    private static void validateOrValue(Field field, String value, boolean list) {
        List<String> values = list ? Arrays.stream(value.split(","))
                .map(String::trim).filter(item -> !item.isEmpty()).toList() : List.of(value);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Expected a non-empty list");
        }
        if (field.getType() == boolean.class || field.getType() == Boolean.class) {
            for (String item : values) {
                if (!item.equalsIgnoreCase("true") && !item.equalsIgnoreCase("false")) {
                    throw new IllegalArgumentException("Expected true or false");
                }
            }
        }
    }

    private static boolean fixedCondition(Field field, QOption[] options, String condition, String annotation) {
        boolean always = false;
        for (QOption option : options) {
            if (option != QOption.EXECUTE_ALWAYS) {
                throw new IllegalStateException(annotation + " on " + field + " does not support " + option);
            }
            always = true;
        }
        if (always && condition.isEmpty()) {
            throw new IllegalStateException(
                    annotation + " on " + field + " requires a non-empty condition with EXECUTE_ALWAYS");
        }
        if (!always && !condition.isEmpty()) {
            throw new IllegalStateException(annotation + " on " + field + " requires EXECUTE_ALWAYS when condition is set");
        }
        return always;
    }

    private static Object fixedValue(Field field, String condition) {
        try {
            if ((field.getType() == boolean.class || field.getType() == Boolean.class)
                    && !condition.equalsIgnoreCase("true") && !condition.equalsIgnoreCase("false")) {
                throw new IllegalArgumentException("Expected true or false");
            }
            return convert(field.getType(), condition);
        } catch (IllegalArgumentException | java.time.DateTimeException exception) {
            throw new IllegalStateException("Invalid fixed condition for " + field, exception);
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private <T> void applyFilters(Class<T> entityType, CriteriaBuilder cb, Root<T> root,
            MultivaluedMap<String, String> parameters, List<Predicate> predicates,
            List<Predicate> alternatives, Set<String> handledOrParameters) {
        QueryableFilterContext<T> context = new QueryableFilterContext<>(cb, root, parameters);
        for (QueryableFilterMarker marker : filterBeans) {
            QueryableFilter filter = (QueryableFilter) marker;
            if (filter.entityType().equals(entityType)) {
                filter.contribute(context);
            }
        }
        if (!context.orParameters().isEmpty() && context.orPredicates().isEmpty()) {
            throw new BadRequestException("Custom OR query parameters produced no predicates");
        }
        predicates.addAll(context.predicates());
        alternatives.addAll(context.orPredicates());
        handledOrParameters.addAll(context.orParameters());
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private <T> void addEqualOrRange(Field field, String prefix, String alias, CriteriaBuilder cb, Root<T> root,
            MultivaluedMap<String, String> parameters, List<Predicate> predicates) {
        String fieldName = field.getName();
        String name = alias == null || alias.isBlank() ? fieldName : alias;
        Class<?> type = field.getType();
        String equalValue = parameters.getFirst(prefix + "." + name);
        String notValue = parameters.getFirst("not." + name);
        String fromValue = aliasValue(parameters, "from", "gt", name);
        String efromValue = aliasValue(parameters, "efrom", "gte", name);
        String toValue = aliasValue(parameters, "to", "lt", name);
        String etoValue = aliasValue(parameters, "eto", "lte", name);

        if (field.isAnnotationPresent(ElementCollection.class)) {
            Attribute<?, ?> attribute = root.getModel().getAttribute(fieldName);
            if (!Collection.class.isAssignableFrom(type) || !(attribute instanceof PluralAttribute<?, ?, ?> plural)) {
                throw new IllegalStateException("@Q requires a plural element collection: " + field);
            }
            if (fromValue != null || efromValue != null || toValue != null || etoValue != null) {
                throw new BadRequestException("Ranges are not supported for collection " + name);
            }
            for (boolean negate : List.of(false, true)) {
                String raw = negate ? notValue : equalValue;
                if (raw == null) {
                    continue;
                }
                Class<?> elementType = plural.getElementType().getJavaType();
                Object value;
                try {
                    if ((elementType == Boolean.class || elementType == boolean.class)
                            && !raw.equalsIgnoreCase("true") && !raw.equalsIgnoreCase("false")) {
                        throw new IllegalArgumentException("Expected true or false");
                    }
                    value = convertQueryValue(elementType, raw, false);
                } catch (IllegalArgumentException | DateTimeException exception) {
                    throw new BadRequestException("Invalid value for " + prefix + "." + name, exception);
                }
                Expression<Collection<Object>> collection = root.get(fieldName);
                predicates.add(negate ? cb.isNotMember(value, collection) : cb.isMember(value, collection));
            }
            return;
        }
        if (equalValue != null) {
            predicates.add(cb.equal(path(root, fieldName), convertQueryValue(type, equalValue, false)));
        }
        if (notValue != null) {
            predicates.add(cb.notEqual(path(root, fieldName), convertQueryValue(type, notValue, false)));
        }
        if (fromValue != null || efromValue != null || toValue != null || etoValue != null) {
            if (!Comparable.class.isAssignableFrom(type)
                    && !type.isPrimitive()
                    && !type.equals(LocalDate.class)
                    && !type.equals(LocalDateTime.class)
                    && !type.equals(Instant.class)
                    && !type.equals(ZonedDateTime.class)) {
                throw new BadRequestException("Ranges require a comparable field: " + name);
            }
        }
        if (fromValue != null) {
            predicates.add(cb.greaterThan(path(root, fieldName), (Comparable) convertQueryValue(type, fromValue, true)));
        }
        if (efromValue != null) {
            predicates.add(
                    cb.greaterThanOrEqualTo(path(root, fieldName), (Comparable) convertQueryValue(type, efromValue, false)));
        }
        if (toValue != null) {
            predicates.add(cb.lessThan(path(root, fieldName), (Comparable) convertQueryValue(type, toValue, false)));
        }
        if (etoValue != null) {
            predicates.add(cb.lessThanOrEqualTo(path(root, fieldName), (Comparable) convertQueryValue(type, etoValue, true)));
        }
    }

    private static String aliasValue(MultivaluedMap<String, String> parameters, String first, String second, String name) {
        String firstName = first + "." + name;
        String secondName = second + "." + name;
        boolean hasFirst = parameters.containsKey(firstName) || parameters.containsKey("or." + firstName);
        boolean hasSecond = parameters.containsKey(secondName) || parameters.containsKey("or." + secondName);
        if (hasFirst && hasSecond) {
            throw new BadRequestException("Use only one of " + firstName + " and " + secondName);
        }
        return parameters.containsKey(firstName) ? parameters.getFirst(firstName) : parameters.getFirst(secondName);
    }

    private <T> void addDirectionalLike(Field field, String name, boolean startsWith, CriteriaBuilder cb, Root<T> root,
            MultivaluedMap<String, String> parameters, List<Predicate> predicates) {
        String value = parameters.getFirst(name);
        if (value != null) {
            String pattern = value.toLowerCase(Locale.ROOT);
            predicates.add(cb.like(cb.lower(path(root, field.getName())),
                    startsWith ? pattern + "%" : "%" + pattern));
        }
    }

    private <T> void addLike(Field field, String parameterName, CriteriaBuilder cb, Root<T> root,
            MultivaluedMap<String, String> parameters, List<Predicate> predicates) {
        String value = parameters.getFirst(parameterName);
        if (value != null) {
            addLikeValue(field, value, cb, root, predicates);
        }
    }

    private <T> void addLikeValue(Field field, String value, CriteriaBuilder cb, Root<T> root,
            List<Predicate> predicates) {
        predicates.add(cb.like(cb.lower(path(root, field.getName())),
                "%" + value.toLowerCase(Locale.ROOT) + "%"));
    }

    private <T> void addNotLike(Field field, String name, CriteriaBuilder cb, Root<T> root,
            MultivaluedMap<String, String> parameters, List<Predicate> predicates) {
        String value = parameters.getFirst(name);
        if (value != null) {
            predicates.add(cb.notLike(cb.lower(path(root, field.getName())),
                    "%" + value.toLowerCase(Locale.ROOT) + "%"));
        }
    }

    private <T> void addEmpty(Field field, String name, boolean negate, CriteriaBuilder cb, Root<T> root,
            MultivaluedMap<String, String> parameters, List<Predicate> predicates) {
        if (parameters.containsKey(name)) {
            predicates.add(negate ? cb.notEqual(path(root, field.getName()), "")
                    : cb.equal(path(root, field.getName()), ""));
        }
    }

    private <T> void addList(Field field, String parameterName, CriteriaBuilder cb, Root<T> root,
            MultivaluedMap<String, String> parameters, List<Predicate> predicates) {
        String value = parameters.getFirst(parameterName);
        if (value == null) {
            return;
        }
        CriteriaBuilder.In<Object> in = cb.in(path(root, field.getName()));
        Arrays.stream(value.split(","))
                .map(item -> item.trim())
                .filter(item -> !item.isEmpty())
                .map(item -> convert(field.getType(), item))
                .forEach(in::value);
        predicates.add(in);
    }

    private <T> void addLikeList(Field field, String parameterName, CriteriaBuilder cb, Root<T> root,
            MultivaluedMap<String, String> parameters, List<Predicate> predicates) {
        String value = parameters.getFirst(parameterName);
        if (value == null) {
            return;
        }
        List<Predicate> alternatives = Arrays.stream(value.split(","))
                .map(item -> item.trim())
                .filter(item -> !item.isEmpty())
                .map(item -> cb.like(cb.lower(path(root, field.getName())),
                        "%" + item.toLowerCase(Locale.ROOT) + "%"))
                .toList();
        if (!alternatives.isEmpty()) {
            predicates.add(cb.or(alternatives));
        }
    }

    private <T> void applyOrder(CriteriaBuilder cb, CriteriaQuery<T> query, Root<T> root,
            String orderBy, Class<T> entityType) {
        if (orderBy == null || orderBy.isBlank()) {
            QOrderBy defaultOrder = entityType.getAnnotation(QOrderBy.class);
            orderBy = defaultOrder == null ? null : defaultOrder.value();
        }
        if (orderBy == null || orderBy.isBlank()) {
            return;
        }
        List<jakarta.persistence.criteria.Order> orders = new ArrayList<>();
        for (String token : orderBy.split(",")) {
            String[] parts = token.trim().split("\\s+");
            String field = parts[0];
            if (fields(root.getJavaType()).stream().noneMatch(candidate -> candidate.getName().equals(field))) {
                throw new IllegalArgumentException("Invalid order field: " + field);
            }
            boolean descending = parts.length > 1 && parts[1].equalsIgnoreCase("desc");
            orders.add(descending ? cb.desc(path(root, field)) : cb.asc(path(root, field)));
        }
        query.orderBy(orders);
    }

    @SuppressWarnings("unchecked")
    private static <T, Y> jakarta.persistence.criteria.Path<Y> path(Root<T> root, String fieldPath) {
        jakarta.persistence.criteria.Path<?> path = root;
        for (String segment : fieldPath.split("\\.")) {
            path = path.get(segment);
        }
        return (jakarta.persistence.criteria.Path<Y>) path;
    }

    private static String parameterName(String prefix, String name, Field field) {
        String actualName = name == null || name.isBlank() ? field.getName() : name;
        return prefix == null || prefix.isBlank() ? actualName : prefix + "." + actualName;
    }

    private static List<Field> fields(Class<?> entityType) {
        List<Field> result = new ArrayList<>();
        for (Class<?> type = entityType; type != null && type != Object.class; type = type.getSuperclass()) {
            result.addAll(List.of(type.getDeclaredFields()));
        }
        return result;
    }

    private Object convertId(Class<?> entityType, String id) {
        Class<?> idType = entityManager.getMetamodel().entity(entityType).getIdType().getJavaType();
        return convert(idType, id);
    }

    private Object identifier(Object entity) {
        return entityManager.getEntityManagerFactory().getPersistenceUnitUtil().getIdentifier(entity);
    }

    private void assignIdentifier(Object entity, Object id) {
        var idAttribute = entityManager.getMetamodel().entity(entity.getClass()).getId(id.getClass());
        try {
            Field field = findField(entity.getClass(), idAttribute.getName());
            field.setAccessible(true);
            field.set(entity, id);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot assign identifier to " + entity.getClass().getName(), exception);
        }
    }

    private Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // Keep walking the entity hierarchy.
            }
            current = current.getSuperclass();
        }
        throw new IllegalStateException("Cannot find identifier field " + name + " in " + type.getName());
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static Object convert(Class<?> type, String value) {
        if (type == String.class)
            return value;
        if (type == long.class || type == Long.class)
            return Long.valueOf(value);
        if (type == int.class || type == Integer.class)
            return Integer.valueOf(value);
        if (type == short.class || type == Short.class)
            return Short.valueOf(value);
        if (type == byte.class || type == Byte.class)
            return Byte.valueOf(value);
        if (type == double.class || type == Double.class)
            return Double.valueOf(value);
        if (type == float.class || type == Float.class)
            return Float.valueOf(value);
        if (type == boolean.class || type == Boolean.class)
            return Boolean.valueOf(value);
        if (type == UUID.class)
            return UUID.fromString(value);
        if (type == BigDecimal.class)
            return new BigDecimal(value);
        if (type == BigInteger.class)
            return new BigInteger(value);
        if (type == Date.class)
            return date(value, false);
        if (type == LocalDate.class)
            return LocalDate.parse(value);
        if (type == LocalDateTime.class)
            return LocalDateTime.parse(value);
        if (type == ZonedDateTime.class)
            return ZonedDateTime.parse(value);
        if (type == Instant.class)
            return Instant.parse(value);
        if (type.isEnum())
            return Enum.valueOf((Class<? extends Enum>) type, value);
        throw new IllegalArgumentException("Unsupported query parameter type: " + type.getName());
    }

    private static Object convertQueryValue(Class<?> type, String value, boolean endOfDay) {
        try {
            return type == Date.class ? date(value, endOfDay) : convert(type, value);
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw new BadRequestException("Invalid value for " + type.getSimpleName() + ": " + value, exception);
        }
    }

    private static Date date(String value, boolean endOfDay) {
        try {
            return Date.from(OffsetDateTime.parse(value).toInstant());
        } catch (DateTimeParseException ignored) {
            // Try local formats using UTC.
        }
        try {
            LocalDate day = LocalDate.parse(value);
            Instant start = day.atStartOfDay().toInstant(ZoneOffset.UTC);
            return Date.from(endOfDay ? day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).minusMillis(1) : start);
        } catch (DateTimeParseException ignored) {
            // Try date-time formats.
        }
        for (DateTimeFormatter formatter : LEGACY_DATE_FORMATS) {
            try {
                return Date.from(LocalDateTime.parse(value, formatter).toInstant(ZoneOffset.UTC));
            } catch (DateTimeParseException ignored) {
                // Try the next supported format.
            }
        }
        throw new DateTimeParseException("Unsupported date format", value, 0);
    }

    private static int integer(MultivaluedMap<String, String> parameters, String name, int defaultValue) {
        String value = parameters.getFirst(name);
        return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private void dispatchBefore(Class<?> entityType, QueryableOperation operation, Object entity, Object id) {
        for (QueryableRsListenerMarker marker : rsListeners) {
            QueryableRsListener listener = (QueryableRsListener) marker;
            if (listener.entityType().equals(entityType)) {
                listener.onBefore(QueryableEvent.before(entityType, operation, entity, id));
                invokeAnnotatedCallbacks(listener, QueryableEvent.before(entityType, operation, entity, id));
            }
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private void dispatchAfter(Class<?> entityType, QueryableOperation operation, Object entity, Object id) {
        QueryableEvent<?> event = QueryableEvent.after(entityType, operation, entity, id);
        afterCommit(() -> {
            for (QueryableRsListenerMarker marker : rsListeners) {
                QueryableRsListener listener = (QueryableRsListener) marker;
                if (!listener.entityType().equals(entityType)) {
                    continue;
                }
                try {
                    listener.onAfter(event);
                    invokeAnnotatedCallbacks(listener, event);
                } catch (Exception exception) {
                    LOG.errorf(exception, "Queryable %s after-listener failed for %s",
                            operation, entityType.getName());
                }
            }
        });
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private <T> void dispatchAfter(Class<T> entityType, QueryableOperation operation, List<T> results) {
        QueryableEvent<T> event = QueryableEvent.after(entityType, operation, results);
        afterCommit(() -> {
            for (QueryableRsListenerMarker marker : rsListeners) {
                QueryableRsListener listener = (QueryableRsListener) marker;
                if (!listener.entityType().equals(entityType)) {
                    continue;
                }
                try {
                    listener.onAfter(event);
                    invokeAnnotatedCallbacks(listener, event);
                } catch (Exception exception) {
                    LOG.errorf(exception, "Queryable %s after-listener failed for %s",
                            operation, entityType.getName());
                }
            }
        });
    }

    private void afterCommit(Runnable callback) {
        int status = transactionSynchronizationRegistry.getTransactionStatus();
        if (status == Status.STATUS_NO_TRANSACTION
                || status == Status.STATUS_COMMITTED
                || status == Status.STATUS_ROLLEDBACK) {
            callback.run();
            return;
        }
        transactionSynchronizationRegistry.registerInterposedSynchronization(new Synchronization() {
            @Override
            public void beforeCompletion() {
            }

            @Override
            public void afterCompletion(int completionStatus) {
                if (completionStatus == Status.STATUS_COMMITTED) {
                    callback.run();
                }
            }
        });
    }

    private void invokeAnnotatedCallbacks(Object listener, QueryableEvent<?> event) {
        Class<? extends Annotation> annotationType = callbackAnnotation(event);
        for (Class<?> type = listener.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getAnnotation(annotationType) == null) {
                    continue;
                }
                validateAndInvoke(listener, method, event);
            }
        }
    }

    private Class<? extends Annotation> callbackAnnotation(QueryableEvent<?> event) {
        return switch (event.operation()) {
            case FETCH -> event.after() ? PostFetch.class : PreFetch.class;
            case LIST -> event.after() ? PostList.class : PreList.class;
            case CREATE -> event.after() ? PostPersist.class : PrePersist.class;
            case UPDATE -> event.after() ? PostUpdate.class : PreUpdate.class;
            case DELETE -> event.after() ? PostDelete.class : PreDelete.class;
        };
    }

    private void validateAndInvoke(Object listener, Method method, QueryableEvent<?> event) {
        if (method.getParameterCount() > 1) {
            throw new IllegalArgumentException(
                    method + " must declare no parameters or one supported lifecycle argument");
        }
        try {
            method.setAccessible(true);
            Object[] arguments = method.getParameterCount() == 0
                    ? new Object[0]
                    : new Object[] { callbackArgument(method.getParameterTypes()[0], method, event) };
            method.invoke(listener, arguments);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot invoke Queryable callback " + method, exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Queryable callback failed: " + method, cause);
        }
    }

    private Object callbackArgument(Class<?> parameterType, Method method, QueryableEvent<?> event) {
        if (parameterType.isAssignableFrom(QueryableEvent.class)) {
            return event;
        }
        if (event.entity().isPresent() && parameterType.isInstance(event.entity().orElseThrow())) {
            return event.entity().orElseThrow();
        }
        if (event.results().isPresent() && parameterType.isAssignableFrom(event.results().orElseThrow().getClass())) {
            return event.results().orElseThrow();
        }
        if (event.id().isPresent() && parameterType.isInstance(event.id().orElseThrow())) {
            return event.id().orElseThrow();
        }
        throw new IllegalArgumentException(method
                + " parameter must be QueryableEvent, the entity, the result List, or the entity id");
    }
}
