package io.quarkiverse.queryable.runtime;

import java.util.Set;

public final class QueryableTypes {
    private static final Set<String> SCALARS = Set.of(
            "java.lang.String", "boolean", "java.lang.Boolean",
            "byte", "java.lang.Byte", "short", "java.lang.Short",
            "int", "java.lang.Integer", "long", "java.lang.Long",
            "float", "java.lang.Float", "double", "java.lang.Double",
            "java.math.BigDecimal", "java.math.BigInteger", "java.util.UUID",
            "java.util.Date", "java.time.LocalDate", "java.time.LocalDateTime",
            "java.time.Instant", "java.time.ZonedDateTime");

    private QueryableTypes() {
    }

    public static boolean isScalar(String name, boolean enumType) {
        return enumType || SCALARS.contains(name);
    }
}
