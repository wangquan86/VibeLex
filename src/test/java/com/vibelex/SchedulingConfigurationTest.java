package com.vibelex;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class SchedulingConfigurationTest {

  @Test
  void backgroundWorkersUseParallelSchedulerAndHalfSecondPolling() throws IOException {
    var environment = new StandardEnvironment();
    var sources =
        new YamlPropertySourceLoader()
            .load("application", new ClassPathResource("application.yml"));
    sources.forEach(environment.getPropertySources()::addLast);

    assertThat(environment.getProperty("spring.task.scheduling.pool.size", Integer.class))
        .isEqualTo(5);
    assertThat(environment.getProperty("vibelex.import.worker.fixed-delay-millis", Long.class))
        .isEqualTo(500L);
    assertThat(environment.getProperty("vibelex.crawling.worker.fixed-delay-millis", Long.class))
        .isEqualTo(500L);
  }
}
