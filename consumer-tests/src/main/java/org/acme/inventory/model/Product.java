package org.acme.inventory.model;

import io.quarkiverse.queryable.runtime.Q;
import io.quarkiverse.queryable.runtime.QFilter;
import io.quarkiverse.queryable.runtime.QLike;
import io.quarkiverse.queryable.runtime.QLogicalDelete;
import io.quarkiverse.queryable.runtime.QOrderBy;
import io.quarkiverse.queryable.runtime.QRs;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

import java.math.BigDecimal;

@Entity
@Table(name = "products")
@QRs("/api/products")
@QOrderBy("name asc")
@FilterDef(name = "Product.minPrice", parameters = @ParamDef(name = "minimum", type = BigDecimal.class))
@Filter(name = "Product.minPrice", condition = "price >= :minimum")
@QFilter(name = "Product.minPrice", queryParam = "min.price", filterParam = "minimum")
public class Product extends PanacheEntityBase {

    @UuidGenerator
    @Id
    public String uuid;

    @QLike
    public String name;

    @Q
    public BigDecimal price;

    @QLogicalDelete
    public boolean active = true;
}
