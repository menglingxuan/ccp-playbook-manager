package ac.domain.clone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class NamingTemplateTest {

    private static final NamingTemplate.Context CONTEXT = new NamingTemplate.Context(
            "IRS-0001", 3, "3f2a", LocalDate.of(2026, 11, 4), "b-01");

    @Test
    void rendersAllSupportedTokens() {
        assertThat(NamingTemplate.render("{base}-{seq:02}", CONTEXT)).isEqualTo("IRS-0001-03");
        assertThat(NamingTemplate.render("{base}_{date:yyyyMMdd}_{batch}_{uuid}", CONTEXT))
                .isEqualTo("IRS-0001_20261104_b-01_3f2a");
        assertThat(NamingTemplate.render("{seq}", CONTEXT)).isEqualTo("3");
    }

    @Test
    void emptyTemplateKeepsTheBaseValue() {
        assertThat(NamingTemplate.render("", CONTEXT)).isEqualTo("IRS-0001");
        assertThat(NamingTemplate.render(null, CONTEXT)).isEqualTo("IRS-0001");
    }

    @Test
    void rejectsUnknownTokens() {
        assertThatThrownBy(() -> NamingTemplate.validate("{unknown}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown");
        assertThatThrownBy(() -> NamingTemplate.render("{seq:abc}", CONTEXT))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
