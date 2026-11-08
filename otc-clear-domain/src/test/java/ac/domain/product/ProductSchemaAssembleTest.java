package ac.domain.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ac.domain.TestSchemas;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductSchemaAssembleTest {

    private static FieldDef field(String key, int order, String header, FieldType type, FieldRole role) {
        return FieldDef.of(key, order, header, List.of(), type, role, false, true, "", "", 1);
    }

    private static FieldDef keyField(String key, int order, String header, FieldType type) {
        return new FieldDef(key, order, header, List.of(), type, FieldRole.BUSINESS, false, true, true, "", "",
                1, List.of(), List.of());
    }

    @Test
    void sortsFieldsAndResolvesDuplicateHeaders() {
        ProductSchema schema = ProductSchema.assemble("P", "Product", "", List.of(
                field("floating", 2, "Rate", FieldType.DECIMAL, FieldRole.BUSINESS),
                field("fixed", 1, "Rate", FieldType.DECIMAL, FieldRole.BUSINESS)));

        assertThat(schema.fields()).extracting(FieldDef::key).containsExactly("fixed", "floating");
        assertThat(schema.fields()).extracting(FieldDef::displayHeader)
                .containsExactly("Rate", "Rate (#2)");
        assertThat(schema.hasDuplicateHeaders()).isTrue();
    }

    @Test
    void schemaHashIsStableAndSensitiveToChanges() {
        ProductSchema first = TestSchemas.small();
        ProductSchema second = TestSchemas.small();
        assertThat(first.schemaHash()).isEqualTo(second.schemaHash()).hasSize(64);

        ProductSchema withTag = ProductSchema.assemble("SMALL", "Small", "", List.of(
                new FieldDef("a", 1, "A", List.of(), FieldType.TEXT, FieldRole.BUSINESS, false, true, false, "",
                        "", 1, List.of("CLEARING"), List.of()),
                field("b", 2, "B", FieldType.TEXT, FieldRole.BUSINESS),
                field("c", 3, "C", FieldType.TEXT, FieldRole.BUSINESS)));
        assertThat(withTag.schemaHash()).isNotEqualTo(first.schemaHash());
    }

    @Test
    void rejectsDuplicateFieldKeys() {
        assertThatThrownBy(() -> ProductSchema.assemble("P", "P", "", List.of(
                field("a", 1, "A", FieldType.TEXT, FieldRole.BUSINESS),
                field("a", 2, "B", FieldType.TEXT, FieldRole.BUSINESS))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate field key");
    }

    @Test
    void rejectsMoreThanOneKeyField() {
        assertThatThrownBy(() -> ProductSchema.assemble("P", "P", "", List.of(
                keyField("a", 1, "A", FieldType.TEXT),
                keyField("b", 2, "B", FieldType.TEXT))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("more than one key field");
    }

    @Test
    void keyFieldMustBeText() {
        assertThatThrownBy(() -> ProductSchema.assemble("P", "P", "", List.of(
                keyField("a", 1, "A", FieldType.DECIMAL))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be TEXT");
    }

    @Test
    void uniqueFieldAndRequiredFieldsAreExposed() {
        ProductSchema schema = TestSchemas.analyzed();

        assertThat(schema.uniqueField()).isNotNull();
        assertThat(schema.uniqueField().key()).isEqualTo("tradeId");
        assertThat(schema.requiredFields()).extracting(FieldDef::key)
                .containsExactly("tradeId", "tradeDate", "notional");
        assertThat(schema.businessFields()).hasSize(3);
    }

    @Test
    void managedColumnsStayOutsideTheTradeBlock() {
        FieldDef managed = new FieldDef("testCaseId", 1, "_TestCaseId", List.of(), FieldType.TEXT,
                FieldRole.MANAGED, true, false, false, "", "", 1, List.of(), List.of());
        FieldDef requiredBusiness = new FieldDef("a", 2, "A", List.of(), FieldType.TEXT, FieldRole.BUSINESS,
                true, true, false, "", "", 1, List.of(), List.of());
        ProductSchema schema = ProductSchema.assemble("P", "P", "", true, List.of(
                managed,
                requiredBusiness,
                field("b", 3, "B", FieldType.TEXT, FieldRole.BUSINESS)));

        assertThat(schema.fields()).hasSize(3);
        assertThat(schema.strictMatch()).isTrue();
        assertThat(schema.tradeFields()).extracting(FieldDef::key).containsExactly("a", "b");
        assertThat(schema.managedFields()).extracting(FieldDef::key).containsExactly("testCaseId");
        assertThat(schema.tradeHeaders()).containsExactly("A", "B");
        assertThat(schema.requiredFields()).extracting(FieldDef::key).containsExactly("a");
    }

    @Test
    void strictMatchIsPartOfTheSchemaHash() {
        ProductSchema lenient = ProductSchema.assemble("P", "P", "", List.of(field("a", 1, "A",
                FieldType.TEXT, FieldRole.BUSINESS)));
        ProductSchema strict = ProductSchema.assemble("P", "P", "", true, List.of(field("a", 1, "A",
                FieldType.TEXT, FieldRole.BUSINESS)));

        assertThat(lenient.strictMatch()).isFalse();
        assertThat(strict.schemaHash()).isNotEqualTo(lenient.schemaHash());
    }

    @Test
    void nonStrictProductsWithDuplicateColumnNamesAreInvalid() {
        ProductSchema lenient = TestSchemas.duplicateRate();

        assertThat(lenient.hasDuplicateTradeHeaders()).isTrue();
        assertThat(lenient.duplicateTradeHeaders()).containsExactly("Rate");
        assertThat(lenient.definitionValid()).isFalse();
    }

    @Test
    void strictProductsTolerateDuplicateColumnNames() {
        ProductSchema strict = TestSchemas.duplicateRateStrict();

        assertThat(strict.hasDuplicateTradeHeaders()).isTrue();
        assertThat(strict.definitionValid()).isTrue();
    }

    @Test
    void uniqueColumnNamesStayValidInBothModes() {
        ProductSchema lenient = TestSchemas.small();
        ProductSchema strict = ProductSchema.assemble("SMALL", "Small", "", true, lenient.fields());

        assertThat(lenient.definitionValid()).isTrue();
        assertThat(strict.definitionValid()).isTrue();
    }

    @Test
    void duplicateDetectionIgnoresCaseAndSurroundingSpaces() {
        ProductSchema schema = ProductSchema.assemble("P", "P", "", List.of(
                field("a", 1, "Rate", FieldType.DECIMAL, FieldRole.BUSINESS),
                field("b", 2, " rate ", FieldType.DECIMAL, FieldRole.BUSINESS)));

        assertThat(schema.hasDuplicateTradeHeaders()).isTrue();
        assertThat(schema.fields()).extracting(FieldDef::displayHeader)
                .containsExactly("Rate", " rate  (#2)");
    }

    @Test
    void managedColumnsNeverBlockTheDefinition() {
        FieldDef managed = new FieldDef("testCaseId", 1, "_TestCaseId", List.of(), FieldType.TEXT,
                FieldRole.MANAGED, false, false, false, "", "", 1, List.of(), List.of());
        FieldDef managedDuplicate = new FieldDef("remarks", 2, "_TestCaseId", List.of(), FieldType.TEXT,
                FieldRole.MANAGED, false, false, false, "", "", 2, List.of(), List.of());
        ProductSchema schema = ProductSchema.assemble("P", "P", "", List.of(managed, managedDuplicate,
                field("a", 3, "A", FieldType.TEXT, FieldRole.BUSINESS)));

        assertThat(schema.hasDuplicateHeaders()).isTrue();
        assertThat(schema.hasDuplicateTradeHeaders()).isFalse();
        assertThat(schema.definitionValid()).isTrue();
    }
}
