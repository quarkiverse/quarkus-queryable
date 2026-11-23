package org.acme.inventory.model;

import io.quarkiverse.queryable.runtime.Q;
import io.quarkiverse.queryable.runtime.QLike;
import io.quarkiverse.queryable.runtime.QLogicalDelete;
import io.quarkiverse.queryable.runtime.QOrderBy;
import io.quarkiverse.queryable.runtime.QRs;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;

@Entity
@Table(name = "products")
@QRs("/api/products")
@QOrderBy("name asc")
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
