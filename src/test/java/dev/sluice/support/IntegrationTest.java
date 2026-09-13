package dev.sluice.support;

import org.springframework.boot.test.context.SpringBootTest;



import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Base for tests that need the full application against a real database.
 * Extend {@link AbstractIntegrationTest} rather than using this directly.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@SpringBootTest
public @interface IntegrationTest {
}
