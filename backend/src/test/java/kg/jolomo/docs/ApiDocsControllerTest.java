package kg.jolomo.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Swagger UI только для dev: страница и оба описания отдаются, а в рабочем профиле маршрутов нет. */
class ApiDocsControllerTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ApiDocsController()).build();

    @Test
    void thePageLoadsSwaggerUiAndPointsAtBothSpecs() throws Exception {
        mvc.perform(get("/swagger"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("SwaggerUIBundle")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/swagger/openapi.yaml")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/swagger/sms-gateway.yaml")))
                // Без пресета и StandaloneLayout список описаний не работает («No API definition provided»).
                .andExpect(content().string(org.hamcrest.Matchers.containsString("swagger-ui-standalone-preset.js")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("StandaloneLayout")))
                // Двойная отправка CSRF: заголовок берётся из cookie jl_csrf.
                .andExpect(content().string(org.hamcrest.Matchers.containsString("X-CSRF-Token")));
    }

    @Test
    void theMainSpecIsServedFromTheBuild() throws Exception {
        String body = mvc.perform(get("/swagger/openapi.yaml")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/yaml"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(body).startsWith("openapi: 3.0.3").contains("/api/v1/auth/otp/request", "/api/v1/me/push-subscriptions");
    }

    @Test
    void theGatewaySpecIsServedToo() throws Exception {
        String body = mvc.perform(get("/swagger/sms-gateway.yaml")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(body).startsWith("openapi: 3.0.3").contains("/api/v1/gateway/");
    }

    @Test
    void onlyTheDevProfileExposesIt() {
        Profile profile = ApiDocsController.class.getAnnotation(Profile.class);

        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly("dev");
    }
}
