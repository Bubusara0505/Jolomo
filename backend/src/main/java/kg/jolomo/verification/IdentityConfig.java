package kg.jolomo.verification;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class IdentityConfig {

    /** Ключ обязателен: без IDENTITY_KEY приложение не стартует. Каталог должен лежать вне репозитория. */
    @Bean
    IdentityFileStore identityFileStore(
            @Value("${jolomo.identity.dir}") String dir, @Value("${jolomo.identity.key:}") String key) {
        return new IdentityFileStore(Path.of(dir), key);
    }
}
