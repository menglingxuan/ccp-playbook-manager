package ac.core.service.product;

import ac.core.persistence.BaseProductRepository;
import ac.core.persistence.ProductRepository;
import ac.domain.product.FieldDef;
import ac.domain.product.FieldRole;
import ac.domain.product.ProductSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Turns persisted product definition rows into runtime {@link ProductSchema} instances. */
@Component
public class ProductSchemaMapper {

    public ProductSchema toSchema(ProductRepository.ProductRow product,
            List<ProductRepository.ProductFieldRow> fields, MetaLookup.Lookup lookup) {
        List<FieldDef> defs = new ArrayList<>(fields.size());
        for (ProductRepository.ProductFieldRow field : fields) {
            defs.add(toFieldDef(field.fieldKey(), field.header(), field.aliases(), field.valueTypeCode(),
                    field.roleCode(), field.tags(), field.required(), field.businessKey(), field.keyFlag(),
                    field.sortOrder(), field.remark(), field.formatHint(), field.clonePattern(), lookup));
        }
        return ProductSchema.assemble(product.code(), product.name(), product.description(),
                product.strictMatch(), defs);
    }

    /** Base product schemas are only used to preview inheritance, not for imports. */
    public ProductSchema toBaseSchema(BaseProductRepository.BaseProductRow base,
            List<BaseProductRepository.BaseFieldRow> fields, MetaLookup.Lookup lookup) {
        List<FieldDef> defs = new ArrayList<>(fields.size());
        for (BaseProductRepository.BaseFieldRow field : fields) {
            defs.add(toFieldDef(field.fieldKey(), field.header(), field.aliases(), field.valueTypeCode(),
                    field.roleCode(), field.tags(), field.required(), field.businessKey(), field.keyFlag(),
                    field.sortOrder(), field.remark(), field.formatHint(), field.clonePattern(), lookup));
        }
        return ProductSchema.assemble(base.code(), base.name(), base.description(), defs);
    }

    private FieldDef toFieldDef(String key, String header, List<String> aliases, String valueTypeCode,
            String roleCode, List<String> tags, boolean required, boolean businessKey, boolean keyFlag,
            int sortOrder, String remark, String formatHint, String clonePattern, MetaLookup.Lookup lookup) {
        MetaLookup.ValueTypeSpec spec = lookup.valueType(valueTypeCode);
        FieldRole role = "MANAGED".equalsIgnoreCase(roleCode == null ? "" : roleCode)
                ? FieldRole.MANAGED : FieldRole.BUSINESS;
        return new FieldDef(key, sortOrder, header, aliases, spec.fieldType(), role, required, businessKey,
                keyFlag, formatHint == null ? "" : formatHint, clonePattern == null ? "" : clonePattern, 1,
                Set.of(), tags, spec.enumValues());
    }
}
