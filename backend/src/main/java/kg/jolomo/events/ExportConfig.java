package kg.jolomo.events;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ExportConfig {

    @Bean
    ExportSettings exportSettings(
            @Value("${jolomo.export.enabled:false}") boolean enabled,
            @Value("${jolomo.export.dir:${user.home}/.jolomo/export}") String dir,
            @Value("${jolomo.export.pseudonym-key:}") String key) {
        return new ExportSettings(enabled, Path.of(dir), key);
    }
}
