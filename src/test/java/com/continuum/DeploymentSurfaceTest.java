package com.continuum;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Vérifie que les expressions de {@link DeploymentSurface} activent les bons composants selon
 * {@code continuum.surface}. Test léger ({@link ApplicationContextRunner}, sans contexte complet ni
 * base), via des beans témoins portant <strong>les mêmes</strong> expressions
 * {@code @ConditionalOnExpression} que les vrais composants (chaînes de sécurité, moteur CCM).
 */
class DeploymentSurfaceTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AppGated.class, AdminGated.class, WorkerGated.class);

    @Test
    void allInOneByDefaultEnablesEverything() {
        runner.run(ctx -> assertThat(ctx).hasBean("app").hasBean("admin").hasBean("worker"));
    }

    @Test
    void allInOneExplicitEnablesEverything() {
        runner.withPropertyValues("continuum.surface=all")
                .run(ctx -> assertThat(ctx).hasBean("app").hasBean("admin").hasBean("worker"));
    }

    @Test
    void appSurfaceEnablesOnlyApp() {
        runner.withPropertyValues("continuum.surface=app")
                .run(ctx -> assertThat(ctx).hasBean("app").doesNotHaveBean("admin").doesNotHaveBean("worker"));
    }

    @Test
    void adminSurfaceEnablesOnlyAdmin() {
        runner.withPropertyValues("continuum.surface=admin")
                .run(ctx -> assertThat(ctx).hasBean("admin").doesNotHaveBean("app").doesNotHaveBean("worker"));
    }

    @Test
    void workerSurfaceEnablesOnlyWorker() {
        runner.withPropertyValues("continuum.surface=worker")
                .run(ctx -> assertThat(ctx).hasBean("worker").doesNotHaveBean("app").doesNotHaveBean("admin"));
    }

    @Configuration
    static class AppGated {
        @Bean
        @ConditionalOnExpression(DeploymentSurface.ON_APP)
        String app() {
            return "app";
        }
    }

    @Configuration
    static class AdminGated {
        @Bean
        @ConditionalOnExpression(DeploymentSurface.ON_ADMIN)
        String admin() {
            return "admin";
        }
    }

    @Configuration
    static class WorkerGated {
        @Bean
        @ConditionalOnExpression(DeploymentSurface.ON_WORKER)
        String worker() {
            return "worker";
        }
    }
}
