package ac.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Only the dictionaries the application itself needs are built in.
 *
 * <p>{@code VALUE_TYPE} and {@code FIELD_ROLE} map to the internal enums and stay protected;
 * {@code FIELD_TAG} and {@code CONV_METHOD} were created as sample data by the first seed, so the user
 * may maintain them like any other set. Removing a value that a field still references must stay
 * impossible, otherwise the field would carry a dangling code.
 */
class MetaBuiltInSetTest extends AbstractIntegrationTest {

    private JsonNode setOf(String code) {
        for (JsonNode entry : api.get("/api/meta/sets").json()) {
            if (code.equals(entry.get("set").get("code").asString())) {
                return entry.get("set");
            }
        }
        throw new IllegalStateException("Meta set " + code + " not found");
    }

    @Test
    void sampleDictionariesAreNotBuiltIn() {
        assertThat(setOf("VALUE_TYPE").get("builtIn").asBoolean()).isTrue();
        assertThat(setOf("FIELD_ROLE").get("builtIn").asBoolean()).isTrue();
        // sample data of the templates: editable and deletable like a user created set
        assertThat(setOf("FIELD_TAG").get("builtIn").asBoolean()).isFalse();
        assertThat(setOf("CONV_METHOD").get("builtIn").asBoolean()).isFalse();
    }

    @Test
    void targetDictionariesCanBeEdited() {
        // renaming is allowed now, so the sample sets are ordinary data
        var renamed = api.patch("/api/meta/sets/FIELD_TAG", api.toJson(Map.of("name", "字段标签（自定义）",
                "description", "用于分类与检索的标签", "sortOrder", 3, "type", "VALUE")));
        assertThat(renamed.ok()).as(renamed.body()).isTrue();
        assertThat(setOf("FIELD_TAG").get("name").asString()).isEqualTo("字段标签（自定义）");
        api.patch("/api/meta/sets/FIELD_TAG", api.toJson(Map.of("name", "字段标签",
                "description", "用于分类与检索的标签", "sortOrder", 3, "type", "VALUE")));
    }

    @Test
    void valueInUseCannotBeRemoved() {
        // the seeded fields carry the CLEARING tag, so its definition is still referenced
        var deleteUsedTag = api.delete("/api/meta/sets/FIELD_TAG/items/CLEARING");
        assertThat(deleteUsedTag.status()).isEqualTo(409);
        assertThat(deleteUsedTag.body()).contains("field definition");

        // a tag nothing uses can be removed, and the same goes for its set once it is empty of usage
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        var created = api.post("/api/meta/sets/FIELD_TAG/items", api.toJson(Map.of(
                "code", "TMP_" + suffix, "label", "Temporary", "sortOrder", 90)));
        assertThat(created.ok()).as(created.body()).isTrue();
        assertThat(api.delete("/api/meta/sets/FIELD_TAG/items/TMP_" + suffix).ok()).isTrue();

        // the set itself is referenced by those tags, so the whole dictionary stays
        assertThat(api.delete("/api/meta/sets/FIELD_TAG").status()).isEqualTo(409);
        // CONV_METHOD is referenced by the VALUE_TYPE value of the same name
        assertThat(api.delete("/api/meta/sets/CONV_METHOD").status()).isEqualTo(409);
    }

    @Test
    void deletingAFreeSetIsPossible() {
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String code = "FREE_" + suffix;
        var created = api.post("/api/meta/sets", api.toJson(Map.of(
                "code", code, "name", "Free set", "type", "VALUE")));
        assertThat(created.ok()).as(created.body()).isTrue();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("code", "A");
        item.put("label", "Value A");
        item.put("sortOrder", 1);
        assertThat(api.post("/api/meta/sets/" + code + "/items", api.toJson(item)).ok()).isTrue();

        assertThat(api.delete("/api/meta/sets/" + code + "/items/A").ok()).isTrue();
        assertThat(api.delete("/api/meta/sets/" + code).ok()).isTrue();
        assertThat(api.get("/api/meta/sets/" + code + "/items").status()).isEqualTo(404);
    }

    @Test
    void tagsStaySelectableForTheFieldEditor() {
        // the tag dictionary still feeds the field editor, it is just no longer protected
        JsonNode options = api.get("/api/meta/options").json();
        assertThat(options.get("FIELD_TAG")).isNotNull();
        List<String> codes = options.get("FIELD_TAG").valueStream()
                .map(node -> node.get("code").asString()).toList();
        assertThat(codes).contains("CLEARING", "REPORT");
        assertThat(api.get("/api/meta/sets/FIELD_TAG/items").ok()).isTrue();
    }
}
