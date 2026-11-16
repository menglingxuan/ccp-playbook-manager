package ac.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The inline limit of the wizard issue lists is a tenant preference with a configured default. */
class IssueLimitSettingTest extends AbstractIntegrationTest {

    @Test
    void storesClampsAndPublishesTheLimit() {
        var initial = api.get("/api/settings/ui");
        assertThat(initial.ok()).as(initial.body()).isTrue();
        int configured = initial.json().get("issueInlineLimit").asInt();
        assertThat(configured).isBetween(5, 200);
        assertThat(api.get("/api/context").json().get("ui").get("issueInlineLimit").asInt())
                .isEqualTo(configured);

        var updated = api.put("/api/settings/ui", api.toJson(Map.of("issueInlineLimit", 25)));
        assertThat(updated.ok()).as(updated.body()).isTrue();
        assertThat(updated.json().get("issueInlineLimit").asInt()).isEqualTo(25);
        assertThat(api.get("/api/settings/ui").json().get("issueInlineLimit").asInt()).isEqualTo(25);
        // the shell context follows the stored preference, so a reload keeps the same behaviour
        assertThat(api.get("/api/context").json().get("ui").get("issueInlineLimit").asInt()).isEqualTo(25);

        // a value below the usable minimum or beyond the cap is clamped instead of rejected
        assertThat(api.put("/api/settings/ui", api.toJson(Map.of("issueInlineLimit", 1)))
                .json().get("issueInlineLimit").asInt()).isEqualTo(5);
        assertThat(api.put("/api/settings/ui", api.toJson(Map.of("issueInlineLimit", 5000)))
                .json().get("issueInlineLimit").asInt()).isEqualTo(200);

        // a patch without the field keeps the stored value
        Map<String, Object> empty = new LinkedHashMap<>();
        assertThat(api.put("/api/settings/ui", api.toJson(empty))
                .json().get("issueInlineLimit").asInt()).isEqualTo(200);

        var restored = api.put("/api/settings/ui", api.toJson(Map.of("issueInlineLimit", configured)));
        assertThat(restored.json().get("issueInlineLimit").asInt()).isEqualTo(configured);
    }

    @Test
    void exportSettingsStayUntouched() {
        var before = api.get("/api/settings").json();
        api.put("/api/settings/ui", api.toJson(Map.of("issueInlineLimit", 12)));
        var after = api.get("/api/settings").json();
        assertThat(after.get("delimiter").asString()).isEqualTo(before.get("delimiter").asString());
        assertThat(after.get("lineEnding").asString()).isEqualTo(before.get("lineEnding").asString());
        assertThat(after.get("managedColumns").asString()).isEqualTo(before.get("managedColumns").asString());
    }
}
