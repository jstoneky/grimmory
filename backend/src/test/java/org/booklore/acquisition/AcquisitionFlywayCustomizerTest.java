package org.booklore.acquisition;

import org.booklore.service.acquisition.AcquisitionFlywayCustomizer;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AcquisitionFlywayCustomizerTest {

    @Test
    void enablesOutOfOrderSoUpstreamMigrationsApplyBelowTheV900Anchor() {
        var configuration = new FluentConfiguration();

        new AcquisitionFlywayCustomizer().customize(configuration);

        assertThat(configuration.isOutOfOrder()).isTrue();
    }
}
