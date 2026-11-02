package kg.jolomo.docs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Swagger UI для разработки и показа: {@code http://localhost:8080/swagger}. Только профиль dev, в рабочей среде маршрутов нет (404).
 * Описания лежат в репозитории (backend/openapi.yaml и docs/sms-gateway-openapi.yaml), сюда они копируются при сборке.
 * Сам интерфейс подгружается из CDN браузером; сервер отдаёт только эту страницу и два файла описания.
 * «Try it out» работает с вашей сессией: cookie уходят сами, заголовок X-CSRF-Token подставляется из cookie jl_csrf.
 */
@RestController
@Profile("dev")
class ApiDocsController {

    private static final MediaType YAML = new MediaType("application", "yaml", StandardCharsets.UTF_8);

    private static final String PAGE = """
            <!doctype html>
            <html lang="ru">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>JolOmo API</title>
              <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/swagger-ui-dist@5.17.14/swagger-ui.css">
            </head>
            <body>
              <div id="ui"></div>
              <script src="https://cdn.jsdelivr.net/npm/swagger-ui-dist@5.17.14/swagger-ui-bundle.js"></script>
              <script src="https://cdn.jsdelivr.net/npm/swagger-ui-dist@5.17.14/swagger-ui-standalone-preset.js"></script>
              <script>
                window.onload = function () {
                  SwaggerUIBundle({
                    dom_id: '#ui',
                    urls: [
                      { url: '/swagger/openapi.yaml', name: 'JolOmo API' },
                      { url: '/swagger/sms-gateway.yaml', name: 'SMS gateway (устройство)' }
                    ],
                    // Список описаний (urls) работает только с режимом StandaloneLayout и его пресетом.
                    presets: [SwaggerUIBundle.presets.apis, SwaggerUIStandalonePreset],
                    layout: 'StandaloneLayout',
                    'urls.primaryName': 'JolOmo API',
                    deepLinking: true,
                    persistAuthorization: true,
                    requestInterceptor: function (req) {
                      // Работаем с тем адресом, с которого открыта страница (порт мог быть изменён).
                      req.url = req.url.replace('http://localhost:8080', window.location.origin);
                      if (!/^(GET|HEAD|OPTIONS)$/i.test(req.method)) {
                        var m = document.cookie.match(/(?:^|; )jl_csrf=([^;]*)/);
                        if (m) { req.headers['X-CSRF-Token'] = decodeURIComponent(m[1]); }
                      }
                      return req;
                    }
                  });
                };
              </script>
            </body>
            </html>
            """;

    @GetMapping(value = "/swagger", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> page() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(PAGE);
    }

    @GetMapping(value = "/swagger/openapi.yaml", produces = "application/yaml")
    ResponseEntity<String> api() throws IOException {
        return yaml("apidocs/openapi.yaml");
    }

    @GetMapping(value = "/swagger/sms-gateway.yaml", produces = "application/yaml")
    ResponseEntity<String> smsGateway() throws IOException {
        return yaml("apidocs/sms-gateway-openapi.yaml");
    }

    private static ResponseEntity<String> yaml(String resource) throws IOException {
        String text = new String(new ClassPathResource(resource).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return ResponseEntity.ok().contentType(YAML).cacheControl(CacheControl.noStore()).body(text);
    }
}
