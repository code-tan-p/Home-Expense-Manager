package com.homeexpense.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The application's notion of "now". Uses the household's time zone (setting {@code home-expense.timezone},
 * default: the computer's own zone) so "today" and month ends are right even on a server set to UTC.
 * Tests may replace the clock.
 */
@Configuration
@EnableScheduling
public class ClockConfig {

    @Bean
    public Clock clock(@Value("${home-expense.timezone:}") String timezone) {
        ZoneId zone = timezone == null || timezone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(timezone.strip());
        return Clock.system(zone);
    }
}
