package io.quarkiverse.queryable.runtime;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import org.jboss.logging.Logger;

import io.quarkiverse.queryable.runtime.spi.QueryableFilterMarker;
import io.quarkiverse.queryable.runtime.spi.QueryableRsListenerMarker;

@ApplicationScoped
public class QueryableEngine {

    private static final Logger LOG = Logger.getLogger(QueryableEngine.class);

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
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<T> countRoot = countQuery.from(entityType);
        countQuery.select(cb.count(countRoot));
        List<Predicate> countPredicates = predicates(entityType, cb, countRoot, parameters);
        if (!countPredicates.isEmpty()) {
            countQuery.where(countPredicates.toArray(Predicate[]::new));
        }
        long total = entityManager.createQuery(countQuery).getSingleResult();

        CriteriaQuery<T> query = cb.createQuery(entityType);
        Root<T> root = query.from(entityType);
        query.select(root);
        List<Predicate> predicates = predicates(entityType, cb, root, parameters);
        if (!predicates.isEmpty()) {
            query.where(predicates.toArray(Predicate[]::new));
        }
        applyOrder(cb, query, root, parameters.getFirst("orderBy"), entityType);

        int startRow = integer(parameters, "startRow", 0);
        int pageSize = integer(parameters, "pageSize", 10);
        var typedQuery = entityManager.createQuery(query);
        if (pageSize > 0) {
            typedQuery.setFirstResult(startRow).setMaxResults(pageSize);
        }
        List<T> result = typedQuery.getResultList();
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
        for (Field field : fields(entityType)) {
            Q equal = field.getAnnotation(Q.class);
            if (equal != null) {
                addEqualOrRange(field, equal.prefix(), equal.name(), cb, root, parameters, predicates);
            }
            QLike like = field.getAnnotation(QLike.class);
            if (like != null) {
                addLike(field, parameterName(like.prefix(), like.name(), field), cb, root, parameters, predicates);
            }
            QList list = field.getAnnotation(QList.class);
            if (list != null) {
                addList(field, parameterName(list.prefix(), list.name(), field), cb, root, parameters, predicates);
            }
            QLikeList likeList = field.getAnnotation(QLikeList.class);
            if (likeList != null) {
                addLikeList(field, parameterName(likeList.prefix(), likeList.name(), field),
                        cb, root, parameters, predicates);
            }
            QNil nil = field.getAnnotation(QNil.class);
            if (nil != null && parameters.containsKey(parameterName(nil.prefix(), nil.name(), field))) {
                predicates.add(cb.isNotNull(path(root, field.getName())));
            }
            QNotNil notNil = field.getAnnotation(QNotNil.class);
            if (notNil != null && parameters.containsKey(parameterName(notNil.prefix(), notNil.name(), field))) {
                predicates.add(cb.isNull(path(root, field.getName())));
            }
            QLogicalDelete logicalDelete = field.getAnnotation(QLogicalDelete.class);
            if (logicalDelete != null) {
                String name = parameterName(logicalDelete.prefix(), logicalDelete.name(), field);
                String value = parameters.getFirst(name);
                predicates.add(cb.equal(path(root, field.getName()), value == null || value.isBlank()
                        ? Boolean.TRUE
                        : convert(Boolean.class, value)));
            }
        }
        applyFilters(entityType, cb, root, parameters, predicates);
        return predicates;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private <T> void applyFilters(Class<T> entityType, CriteriaBuilder cb, Root<T> root,
            MultivaluedMap<String, String> parameters, List<Predicate> predicates) {
        QueryableFilterContext<T> context = new QueryableFilterContext<>(cb, root, parameters);
        for (QueryableFilterMarker marker : filterBeans) {
            QueryableFilter filter = (QueryableFilter) marker;
            if (filter.entityType().equals(entityType)) {
                filter.contribute(context);
            }
        }
        predicates.addAll(context.predicates());
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private <T> void addEqualOrRange(Field field, String prefix, String alias, CriteriaBuilder cb, Root<T> root,
            MultivaluedMap<String, String> parameters, List<Predicate> predicates) {
        String fieldName = field.getName();
        String name = alias == null || alias.isBlank() ? fieldName : alias;
        Class<?> type = field.getType();
        String equalValue = parameters.getFirst(prefix + "." + name);
        String fromValue = parameters.getFirst("from." + name);
        String toValue = parameters.getFirst("to." + name);

        if (equalValue != null) {
            predicates.add(cb.equal(path(root, fieldName), convert(type, equalValue)));
        }
        if (fromValue != null) {
            if (!Comparable.class.isAssignableFrom(type)
                    && !type.isPrimitive()
                    && !type.equals(LocalDate.class)
                    && !type.equals(LocalDateTime.class)
                    && !type.equals(Instant.class)
                    && !type.equals(ZonedDateTime.class)) {
                throw new IllegalArgumentException("from." + name + " requires a comparable field");
            }
            predicates.add(cb.greaterThanOrEqualTo(path(root, fieldName), (Comparable) convert(type, fromValue)));
        }
        if (toValue != null) {
            predicates.add(cb.lessThanOrEqualTo(path(root, fieldName), (Comparable) convert(type, toValue)));
        }
    }

    private <T> void addLike(Field field, String parameterName, CriteriaBuilder cb, Root<T> root,
            MultivaluedMap<String, String> parameters, List<Predicate> predicates) {
        String value = parameters.getFirst(parameterName);
        if (value != null) {
            predicates.add(cb.like(cb.lower(path(root, field.getName())),
                    "%" + value.toLowerCase(Locale.ROOT) + "%"));
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
            predicates.add(cb.or(alternatives.toArray(Predicate[]::new)));
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
