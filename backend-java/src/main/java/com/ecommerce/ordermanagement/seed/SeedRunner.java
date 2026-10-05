package com.ecommerce.ordermanagement.seed;

import com.ecommerce.ordermanagement.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Batch entry point replacing {@code python -m scripts.seed --reset}.
 *
 * <pre>
 *   java -jar app.jar --app.seed.run=true --app.seed.reset=true \
 *        --spring.main.web-application-type=none
 * </pre>
 *
 * <p>It only runs when {@code --app.seed.run=true} is passed, so a normal server
 * start is unaffected. With {@code web-application-type=none} the JVM exits as
 * soon as the runner returns, and a thrown verification failure propagates out
 * of {@code main} as a non-zero exit code.</p>
 */
@Configuration
public class SeedRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);

    @Bean
    @ConditionalOnProperty(name = "app.seed.run", havingValue = "true")
    public ApplicationRunner seedApplicationRunner(SeedService seedService, AppProperties props) {
        return (ApplicationArguments args) -> {
            Map<String, Object> report = seedService.runSeed(
                    props.getSeedDefault(),
                    SeedService.parseAnchor(props.getSeedAnchorDate()),
                    args.containsOption("app.seed.reset"));
            report.forEach((name, detail) -> log.info("{}: {}", name, detail));
            log.info("seed complete");
        };
    }
}