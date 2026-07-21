package org.booklore.service.acquisition;

import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.stereotype.Component;

/**
 * The fork's migrations are anchored at V900+ so they never collide with
 * upstream's numbering. Once any V900+ migration has been applied, every
 * future upstream migration (V141, V145, ...) arrives "below" the highest
 * applied version and is rejected by Flyway's default validation. Out-of-order
 * mode is therefore a permanent requirement of the fork's numbering scheme.
 */
@Component
public class AcquisitionFlywayCustomizer implements FlywayConfigurationCustomizer {

    @Override
    public void customize(org.flywaydb.core.api.configuration.FluentConfiguration configuration) {
        configuration.outOfOrder(true);
    }
}
