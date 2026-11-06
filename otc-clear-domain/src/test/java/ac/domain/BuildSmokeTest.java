package ac.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BuildSmokeTest {

    @Test
    void buildToolchainIsUsable() {
        assertThat(Runtime.version().feature()).isGreaterThanOrEqualTo(21);
    }
}
