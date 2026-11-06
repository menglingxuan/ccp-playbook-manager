package ac.domain.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ac.domain.product.products.CcsIrsCase;
import ac.domain.product.products.IrsCase;
import ac.domain.product.products.OisCase;
import org.junit.jupiter.api.Test;

class ProductSchemaFactoryTest {

    @Test
    void buildsOrderedSchemaWithDuplicateHeaders() {
        ProductSchema schema = ProductSchemaFactory.create(IrsCase.class);

        assertThat(schema.code()).isEqualTo("IRS");
        assertThat(schema.fields()).extracting(FieldDef::order).isSorted();
        assertThat(schema.hasDuplicateHeaders()).isTrue();
        assertThat(schema.fields()).filteredOn(field -> field.header().equals("Rate"))
                .extracting(FieldDef::displayHeader)
                .containsExactly("Rate", "Rate (#2)");
        assertThat(schema.uniqueField()).isNotNull();
        assertThat(schema.uniqueField().key()).isEqualTo("tradeId");
    }

    @Test
    void schemaHashIsStableAndDiffersPerProduct() {
        String first = ProductSchemaFactory.create(IrsCase.class).schemaHash();
        String second = ProductSchemaFactory.create(IrsCase.class).schemaHash();
        String other = ProductSchemaFactory.create(OisCase.class).schemaHash();

        assertThat(first).isEqualTo(second).hasSize(64);
        assertThat(first).isNotEqualTo(other);
    }

    @Test
    void registryResolvesCodesCaseInsensitively() {
        ProductRegistry registry = ProductRegistry.of(IrsCase.class, OisCase.class, CcsIrsCase.class);

        assertThat(registry.all()).hasSize(3);
        assertThat(registry.require("irs").name()).isEqualTo("Interest Rate Swap");
        assertThat(registry.find("unknown")).isEmpty();
    }

    @Test
    void rejectsDuplicateKeys() {
        assertThatThrownBy(() -> ProductSchemaFactory.create(DuplicateKeyProduct.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate field key");
    }

    @Test
    void rejectsDuplicateOrders() {
        assertThatThrownBy(() -> ProductSchemaFactory.create(DuplicateOrderProduct.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate field order");
    }

    @Test
    void rejectsTwoKeyFields() {
        assertThatThrownBy(() -> ProductSchemaFactory.create(TwoKeyProduct.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("more than one KEY field");
    }

    @ProductDefinition(code = "DUP", name = "Duplicate keys")
    @ProductField(key = "a", order = 1, header = "A")
    @ProductField(key = "a", order = 2, header = "B")
    static class DuplicateKeyProduct {
    }

    @ProductDefinition(code = "ORD", name = "Duplicate orders")
    @ProductField(key = "a", order = 1, header = "A")
    @ProductField(key = "b", order = 1, header = "B")
    static class DuplicateOrderProduct {
    }

    @ProductDefinition(code = "KEY", name = "Two keys")
    @ProductField(key = "a", order = 1, header = "A", role = FieldRole.KEY)
    @ProductField(key = "b", order = 2, header = "B", role = FieldRole.KEY)
    static class TwoKeyProduct {
    }
}
