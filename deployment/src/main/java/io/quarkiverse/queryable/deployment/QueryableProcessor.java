package io.quarkiverse.queryable.deployment;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.FieldInfo;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.Type;

import io.quarkiverse.queryable.runtime.QueryableTypes;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.GeneratedBeanBuildItem;
import io.quarkus.arc.deployment.GeneratedBeanGizmoAdaptor;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveMethodBuildItem;
import io.quarkus.gizmo.ClassCreator;
import io.quarkus.gizmo.ClassOutput;
import io.quarkus.gizmo.MethodCreator;
import io.quarkus.gizmo.MethodDescriptor;
import io.quarkus.rest.data.panache.deployment.ResourceMetadata;
import io.quarkus.rest.data.panache.deployment.RestDataResourceBuildItem;
import io.quarkus.rest.data.panache.deployment.properties.MethodProperties;
import io.quarkus.rest.data.panache.deployment.properties.ResourceProperties;
import io.quarkus.rest.data.panache.deployment.properties.ResourcePropertiesBuildItem;
import io.quarkus.rest.data.panache.deployment.utils.EntityTypeUtils;
import io.quarkus.resteasy.reactive.spi.GeneratedJaxRsResourceBuildItem;
import io.quarkus.resteasy.reactive.spi.GeneratedJaxRsResourceGizmoAdaptor;

class QueryableProcessor {

    private static final String FEATURE = "queryable";
    private static final DotName QRS = DotName.createSimple("io.quarkiverse.queryable.runtime.QRs");
    private static final DotName Q = DotName.createSimple("io.quarkiverse.queryable.runtime.Q");
    private static final DotName Q_LIKE = DotName.createSimple("io.quarkiverse.queryable.runtime.QLike");
    private static final DotName Q_R_LIKE = DotName.createSimple("io.quarkiverse.queryable.runtime.QRLike");
    private static final DotName Q_L_LIKE = DotName.createSimple("io.quarkiverse.queryable.runtime.QLLike");
    private static final DotName Q_LIST = DotName.createSimple("io.quarkiverse.queryable.runtime.QList");
    private static final DotName Q_LIKE_LIST = DotName.createSimple("io.quarkiverse.queryable.runtime.QLikeList");
    private static final DotName Q_NIL = DotName.createSimple("io.quarkiverse.queryable.runtime.QNil");
    private static final DotName Q_NOT_NIL = DotName.createSimple("io.quarkiverse.queryable.runtime.QNotNil");
    private static final DotName Q_LOGICAL_DELETE = DotName.createSimple(
            "io.quarkiverse.queryable.runtime.QLogicalDelete");
    private static final DotName Q_FILTER = DotName.createSimple("io.quarkiverse.queryable.runtime.QFilter");
    private static final DotName Q_FILTERS = DotName.createSimple("io.quarkiverse.queryable.runtime.QFilter$List");
    private static final DotName ELEMENT_COLLECTION = DotName.createSimple("jakarta.persistence.ElementCollection");
    private static final DotName TRANSIENT = DotName.createSimple("jakarta.persistence.Transient");
    private static final List<String> COMPARISON_PREFIXES = List.of("from", "gt", "efrom", "gte",
            "to", "lt", "eto", "lte");
    private static final List<DotName> CALLBACK_ANNOTATIONS = List.of(
            DotName.createSimple("io.quarkiverse.queryable.runtime.PreFetch"),
            DotName.createSimple("io.quarkiverse.queryable.runtime.PostFetch"),
            DotName.createSimple("io.quarkiverse.queryable.runtime.PreList"),
            DotName.createSimple("io.quarkiverse.queryable.runtime.PostList"),
            DotName.createSimple("io.quarkiverse.queryable.runtime.PrePersist"),
            DotName.createSimple("io.quarkiverse.queryable.runtime.PostPersist"),
            DotName.createSimple("io.quarkiverse.queryable.runtime.PreUpdate"),
            DotName.createSimple("io.quarkiverse.queryable.runtime.PostUpdate"),
            DotName.createSimple("io.quarkiverse.queryable.runtime.PreDelete"),
            DotName.createSimple("io.quarkiverse.queryable.runtime.PostDelete"));

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    AdditionalBeanBuildItem registerRuntime() {
        return AdditionalBeanBuildItem.unremovableOf(
                "io.quarkiverse.queryable.runtime.QueryableEngine");
    }

    @BuildStep
    ReflectiveClassBuildItem registerFilterAnnotations() {
        return ReflectiveClassBuildItem.builder(
                "io.quarkiverse.queryable.runtime.QFilter",
                "io.quarkiverse.queryable.runtime.QFilter$List",
                "org.hibernate.annotations.Filter",
                "org.hibernate.annotations.Filters")
                .methods().reason("Queryable Hibernate filter annotations").build();
    }

    @BuildStep
    void registerCallbackMethods(CombinedIndexBuildItem combinedIndex,
            BuildProducer<ReflectiveMethodBuildItem> reflectiveMethods) {
        for (DotName annotation : CALLBACK_ANNOTATIONS) {
            for (AnnotationInstance callback : combinedIndex.getIndex().getAnnotations(annotation)) {
                if (callback.target().kind() == AnnotationTarget.Kind.METHOD) {
                    reflectiveMethods.produce(new ReflectiveMethodBuildItem(
                            "Queryable REST lifecycle callback", callback.target().asMethod()));
                }
            }
        }
    }

    @BuildStep
    void generateResources(CombinedIndexBuildItem combinedIndex,
            BuildProducer<GeneratedJaxRsResourceBuildItem> resourceProducer,
            BuildProducer<GeneratedBeanBuildItem> beanProducer,
            BuildProducer<RestDataResourceBuildItem> restDataResources,
            BuildProducer<ResourcePropertiesBuildItem> resourceProperties) {
        ClassOutput jaxRsOutput = new GeneratedJaxRsResourceGizmoAdaptor(resourceProducer);
        ClassOutput beanOutput = new GeneratedBeanGizmoAdaptor(beanProducer);
        for (AnnotationInstance annotation : combinedIndex.getIndex().getAnnotations(QRS)) {
            if (annotation.target().kind() != AnnotationTarget.Kind.CLASS) {
                continue;
            }
            ClassInfo entity = annotation.target().asClass();
            String entityName = entity.name().toString();
            String resourceName = generatedResourceName(entityName);
            generateResource(jaxRsOutput, resourceName, entityName, annotation,
                    queryParameters(entity, combinedIndex.getIndex()));
            String adapterName = resourceName + "RestDataAdapter";
            generateRestDataAdapter(beanOutput, adapterName, entityName);

            restDataResources.produce(new RestDataResourceBuildItem(
                    new ResourceMetadata(adapterName, adapterName, null, entityName,
                            idType(combinedIndex.getIndex(), entity),
                            EntityTypeUtils.getEntityFields(combinedIndex.getIndex(), entityName))));

            MethodProperties listProperties = new MethodProperties(
                    false, "", new String[0], List.of());
            ResourceProperties properties = new ResourceProperties(
                    true, annotation.value().asString(), true, false, "",
                    new String[0], false, List.of(), Map.of("list", listProperties));
            resourceProperties.produce(new ResourcePropertiesBuildItem(adapterName, properties));
        }
    }

    private String generatedResourceName(String entityName) {
        String simpleName = entityName.substring(entityName.lastIndexOf('.') + 1);
        int modelSegment = entityName.indexOf(".model.");
        if (modelSegment >= 0) {
            return entityName.substring(0, modelSegment)
                    + ".service.rs."
                    + entityName.substring(modelSegment + ".model.".length(),
                            entityName.length() - simpleName.length())
                    + simpleName
                    + "QueryableResource";
        }
        int packageEnd = entityName.lastIndexOf('.');
        return entityName.substring(0, packageEnd) + ".service.rs."
                + simpleName + "QueryableResource";
    }

    private void generateResource(ClassOutput classOutput, String resourceName, String entityName,
            AnnotationInstance annotation, List<String> queryParameters) {
        try (ClassCreator creator = ClassCreator.builder()
                .classOutput(classOutput)
                .className(resourceName)
                .superClass("io.quarkiverse.queryable.runtime.QueryableGeneratedResource")
                .build()) {
            creator.addAnnotation(Path.class).addValue("value", annotation.value().asString());
            creator.addAnnotation(Produces.class).addValue("value", new String[] { MediaType.APPLICATION_JSON });
            creator.addAnnotation(Consumes.class).addValue("value", new String[] { MediaType.APPLICATION_JSON });

            MethodCreator constructor = creator.getMethodCreator("<init>", void.class);
            constructor.setParameterNames(new String[0]);
            constructor.invokeSpecialMethod(
                    MethodDescriptor.ofConstructor(
                            "io.quarkiverse.queryable.runtime.QueryableGeneratedResource", Class.class),
                    constructor.getThis(), constructor.loadClass(entityName));
            constructor.returnVoid();

            createListMethod(creator, queryParameters);
        }
    }

    private void generateRestDataAdapter(ClassOutput output, String adapterName, String entityName) {
        try (ClassCreator creator = ClassCreator.builder()
                .classOutput(output)
                .className(adapterName)
                .superClass("io.quarkiverse.queryable.runtime.QueryablePanacheAdapter")
                .build()) {
            creator.addAnnotation(jakarta.enterprise.context.ApplicationScoped.class);
            MethodCreator constructor = creator.getMethodCreator("<init>", void.class);
            constructor.invokeSpecialMethod(
                    MethodDescriptor.ofConstructor(
                            "io.quarkiverse.queryable.runtime.QueryablePanacheAdapter", Class.class),
                    constructor.getThis(), constructor.loadClass(entityName));
            constructor.returnVoid();
        }
    }

    private String idType(IndexView index, ClassInfo entity) {
        ClassInfo current = entity;
        while (current != null) {
            for (FieldInfo field : current.fields()) {
                if (field.hasAnnotation(DotName.createSimple("jakarta.persistence.Id"))) {
                    return field.type().name().toString();
                }
            }
            if (current.superName() == null) {
                return null;
            }
            current = index.getClassByName(current.superName());
        }
        throw new IllegalStateException("No @Id field found for " + entity.name());
    }

    private void createListMethod(ClassCreator creator, List<String> queryParameters) {
        List<Class<?>> parameterTypes = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<String> defaults = new ArrayList<>();
        addParameter(parameterTypes, names, defaults, "startRow", String.class, "0");
        addParameter(parameterTypes, names, defaults, "pageSize", String.class, "10");
        addParameter(parameterTypes, names, defaults, "orderBy", String.class, null);
        for (String queryParameter : queryParameters) {
            addParameter(parameterTypes, names, defaults, queryParameter, String.class, null);
        }
        parameterTypes.add(UriInfo.class);
        names.add("uriInfo");
        defaults.add(null);

        MethodCreator method = creator.getMethodCreator(
                "list", Response.class, parameterTypes.toArray(Class<?>[]::new));
        method.addAnnotation(GET.class);
        for (int index = 0; index < names.size(); index++) {
            if ("uriInfo".equals(names.get(index))) {
                method.getParameterAnnotations(index).addAnnotation(Context.class);
                continue;
            }
            method.getParameterAnnotations(index).addAnnotation(QueryParam.class)
                    .addValue("value", names.get(index));
            if (defaults.get(index) != null) {
                method.getParameterAnnotations(index).addAnnotation(DefaultValue.class)
                        .addValue("value", defaults.get(index));
            }
        }
        method.returnValue(method.invokeSpecialMethod(
                MethodDescriptor.ofMethod(
                        "io.quarkiverse.queryable.runtime.QueryableGeneratedResource",
                        "listInternal", Response.class, UriInfo.class),
                method.getThis(), method.getMethodParam(names.size() - 1)));
    }

    private List<String> queryParameters(ClassInfo entity, IndexView index) {
        Set<String> result = new LinkedHashSet<>();
        Set<String> protectedParameters = new LinkedHashSet<>();
        List<FieldInfo> fields = new ArrayList<>();
        for (ClassInfo current = entity; current != null; current = index.getClassByName(current.superName())) {
            fields.addAll(current.fields());
            if (current.superName() == null) {
                break;
            }
        }
        for (ClassInfo current = entity; current != null; current = index.getClassByName(current.superName())) {
            List<AnnotationInstance> filters = current.declaredAnnotationsWithRepeatable(Q_FILTER, Q_FILTERS);
            if (!filters.isEmpty()) {
                for (AnnotationInstance filter : filters) {
                    String name = filter.value("queryParam").asString();
                    result.add(name);
                    protectedParameters.add(name);
                }
                break;
            }
            if (current.superName() == null) {
                break;
            }
        }
        for (FieldInfo field : fields) {
            AnnotationInstance logicalDelete = field.annotation(Q_LOGICAL_DELETE);
            if (logicalDelete != null) {
                protectedParameters.add(parameterName(field, logicalDelete, "obj", false));
            }
            AnnotationInstance equal = field.annotation(Q);
            if (equal != null && executeAlways(equal)) {
                protectedParameters.add(parameterName(field, equal, "obj", true));
                String name = fieldName(field, equal);
                protectedParameters.add("not." + name);
                for (String operator : COMPARISON_PREFIXES) {
                    protectedParameters.add(operator + "." + name);
                }
            }
            AnnotationInstance like = field.annotation(Q_LIKE);
            if (like != null && executeAlways(like)) {
                protectedParameters.add(parameterName(field, like, "like", false));
            }
        }
        for (FieldInfo field : fields) {
            boolean automatic = (entity.declaredAnnotation(Q) != null || field.declaringClass().declaredAnnotation(Q) != null)
                    && !field.hasAnnotation(Q)
                    && automaticField(field, index);
            if (automatic) {
                addQueryParameter(result, "obj." + field.name(), protectedParameters);
            } else {
                addParameter(result, field, Q, "obj", protectedParameters);
            }
            addParameter(result, field, Q_LIKE, "like", protectedParameters);
            addParameter(result, field, Q_R_LIKE, "rlike", protectedParameters);
            addParameter(result, field, Q_L_LIKE, "llike", protectedParameters);
            addParameter(result, field, Q_LIST, "obj", protectedParameters);
            addParameter(result, field, Q_LIKE_LIST, "like", protectedParameters);
            addParameter(result, field, Q_NIL, "nil", protectedParameters);
            addParameter(result, field, Q_NOT_NIL, "notNil", protectedParameters);
            addParameter(result, field, Q_LOGICAL_DELETE, "obj", protectedParameters);
            if (automatic || field.hasAnnotation(Q) && !executeAlways(field.annotation(Q))) {
                String name = automatic ? field.name() : fieldName(field, field.annotation(Q));
                addQueryParameter(result, "not." + name, protectedParameters);
                if (!field.hasAnnotation(ELEMENT_COLLECTION)) {
                    for (String operator : COMPARISON_PREFIXES) {
                        addQueryParameter(result, operator + "." + name, protectedParameters);
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    private boolean automaticField(FieldInfo field, IndexView index) {
        if (Modifier.isStatic(field.flags()) || Modifier.isTransient(field.flags())
                || field.hasAnnotation(TRANSIENT) || field.hasAnnotation(Q_LOGICAL_DELETE)) {
            return false;
        }
        Type type = field.type();
        if (field.hasAnnotation(ELEMENT_COLLECTION)) {
            if (type.kind() != Type.Kind.PARAMETERIZED_TYPE
                    || !Set.of("java.util.Collection", "java.util.List", "java.util.Set").contains(type.name().toString())
                    || type.asParameterizedType().arguments().size() != 1) {
                return false;
            }
            type = type.asParameterizedType().arguments().get(0);
        }
        if (type.kind() != Type.Kind.CLASS && type.kind() != Type.Kind.PRIMITIVE) {
            return false;
        }
        ClassInfo typeInfo = index.getClassByName(type.name());
        boolean enumType = typeInfo != null && (typeInfo.flags() & 0x4000) != 0;
        return QueryableTypes.isScalar(type.name().toString(), enumType);
    }

    private void addParameter(Set<String> result, FieldInfo field, DotName annotationName,
            String defaultPrefix, Set<String> protectedParameters) {
        AnnotationInstance annotation = field.annotation(annotationName);
        if (annotation == null) {
            return;
        }
        if ((annotationName.equals(Q) || annotationName.equals(Q_LIKE)) && executeAlways(annotation)) {
            return;
        }
        String name = parameterName(field, annotation, defaultPrefix, annotationName.equals(Q));
        if (annotationName.equals(Q_LOGICAL_DELETE)) {
            result.add(name);
        } else {
            addQueryParameter(result, name, protectedParameters);
        }
    }

    private void addQueryParameter(Set<String> result, String name, Set<String> protectedParameters) {
        result.add(name);
        if (!protectedParameters.contains(name)) {
            result.add("or." + name);
        }
    }

    private String parameterName(FieldInfo field, AnnotationInstance annotation, String defaultPrefix, boolean equal) {
        org.jboss.jandex.AnnotationValue prefixValue = annotation.value("prefix");
        String prefix = prefixValue == null ? defaultPrefix : prefixValue.asString();
        String name = fieldName(field, annotation);
        return equal || !prefix.isBlank() ? prefix + "." + name : name;
    }

    private String fieldName(FieldInfo field, AnnotationInstance annotation) {
        org.jboss.jandex.AnnotationValue nameValue = annotation.value("name");
        String name = nameValue == null ? null : nameValue.asString();
        return name == null || name.isBlank() ? field.name() : name;
    }

    private boolean executeAlways(AnnotationInstance annotation) {
        org.jboss.jandex.AnnotationValue options = annotation.value("options");
        return options != null && Arrays.asList(options.asEnumArray()).contains("EXECUTE_ALWAYS");
    }

    private static void addParameter(List<Class<?>> types, List<String> names, List<String> defaults,
            String name, Class<?> type, String defaultValue) {
        types.add(type);
        names.add(name);
        defaults.add(defaultValue);
    }
}
