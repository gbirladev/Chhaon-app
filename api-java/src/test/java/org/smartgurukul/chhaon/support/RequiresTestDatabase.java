package org.smartgurukul.chhaon.support;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Skips the annotated test class (with a clear message) when the throwaway test database is not
 * reachable, so a machine without it does not fail the build. See scripts/make-test-db.sh.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(TestDatabaseCondition.class)
public @interface RequiresTestDatabase {
}
