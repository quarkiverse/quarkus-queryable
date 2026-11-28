package org.acme.inventory.model;

import io.quarkiverse.queryable.runtime.Q;
import io.quarkiverse.queryable.runtime.QFilter;
import io.quarkiverse.queryable.runtime.QLike;
import io.quarkiverse.queryable.runtime.QLikeList;
import io.quarkiverse.queryable.runtime.QLogicalDelete;
import io.quarkiverse.queryable.runtime.QOption;
import io.quarkiverse.queryable.runtime.QOrderBy;
import io.quarkiverse.queryable.runtime.QRs;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Entity;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Entity
@Q
@Table(name = "products")
@QRs("/api/products")
@QOrderBy("name asc")
@FilterDef(name = "Product.minPrice", parameters = @ParamDef(name = "minimum", type = BigDecimal.class))
@Filter(name = "Product.minPrice", condition = "price >= :minimum")
@QFilter(name = "Product.minPrice", queryParam = "min.price", filterParam = "minimum")
@FilterDef(name = "Product.premium")
@Filter(name = "Product.premium", condition = "price >= 20")
@QFilter(name = "Product.premium", queryParam = "only.premium", options = QOption.WITHOUT_PARAMETERS)
public class Product extends PanacheEntityBase {

    @UuidGenerator
    @Id
    public String uuid;

    @QLike
    public String name;

    @QLikeList
    public String tags;

    @Q
    public BigDecimal price;

    @Q(condition = "PUBLIC", options = QOption.EXECUTE_ALWAYS)
    public String visibility;

    @QLogicalDelete
    public boolean active = true;

    @Temporal(TemporalType.TIMESTAMP)
    public Date availableOn;

    @ElementCollection
    public List<String> labels = new ArrayList<>();
}
