package za.co.fnb.dcre.psx;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import za.co.fnb.dcre.psx.service.ReaderService;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Red-proofs the config-prefix rename dcre.cix -> dcre.psx.
 *
 * <p>The failure this exists for has shipped THREE times in this estate and is
 * invisible: move the placeholder in Java, leave the key in application.yml under
 * the old prefix, and the property binds NOTHING. Every reader falls silently to
 * its constant default, the container starts, the suite is green and the build
 * exits 0. Nothing anywhere reports a problem, because a default IS a legal
 * answer.
 *
 * <p>So this asserts the key is POPULATED, never that a lookup returns something.
 * A lookup returns 10000 either way. The two sides are joined mechanically: the
 * placeholder is read back off {@link ReaderService}'s constructor annotation, so
 * renaming the Java side without the yml side fails here rather than at 3am.
 */
class ConfigPrefixBindingTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^:}]+)");

    /** The constant fallback in the annotation. If the binding breaks, THIS is what is used. */
    private static final int CONSTANT_DEFAULT = 10000;

    @Test
    void theSliceSizePlaceholderResolvesToAKeyThatApplicationYmlActuallyDeclares() throws Exception {
        String key = sliceSizePlaceholderKey();

        assertThat(key)
                .as("the Java side must read the PAY prefix, not the collections one it was forked from")
                .isEqualTo("dcre.psx.ingest-slice-size");

        PropertySource<?> yml = applicationYml();
        assertThat(yml.containsProperty(key))
                .as("application.yml must DECLARE %s. If this is red, the Java placeholder was"
                        + " renamed and the yml key was not: the property binds nothing, every"
                        + " read falls to the constant default %d, and the build still exits 0",
                        key, CONSTANT_DEFAULT)
                .isTrue();

        // Control: the assertion above is a real read of a real file, not an
        // empty parse that would call any key present.
        assertThat(yml.containsProperty("dcre.psx.no-such-key"))
                .as("control: the loaded yml answers false for a key it does not hold")
                .isFalse();
        assertThat(yml.containsProperty("dcre.batch.table-prefix"))
                .as("control: the loaded yml answers true for a key it does hold")
                .isTrue();
    }

    /**
     * The collections prefix must be gone from the yml as well as from the Java.
     * A leftover dcre.cix block binds to nothing and reads as live configuration
     * to the next person, which is how the drift starts again.
     */
    @Test
    void noCollectionsPrefixSurvivesAnywhereInTheConfig() throws Exception {
        PropertySource<?> yml = applicationYml();
        assertThat(yml.containsProperty("dcre.cix.ingest-slice-size"))
                .as("a surviving dcre.cix key would be dead config that looks alive")
                .isFalse();

        // The batch metadata prefix and the Liquibase history tables carry the
        // same rename, and the same silent-failure shape: a PSX pod writing
        // CIX_BATCH_ rows would share a JobRepository with a collections service.
        assertThat(yml.getProperty("dcre.batch.table-prefix"))
                .as("batch metadata is per service; the prefix must be PSX_BATCH_")
                .isEqualTo("PSX_BATCH_");
        assertThat(String.valueOf(yml.getProperty("spring.liquibase.database-change-log-table")))
                .as("per-service Liquibase history in dcre_pay")
                .isEqualTo("psx_databasechangelog");
    }

    /**
     * The datasource points at dcre_pay, and the placeholder is the PAY one.
     * Reading DCRE_DB_URL here would silently inherit the collections URL from a
     * shared environment and write payments verdicts into dcre_col.
     */
    @Test
    void theDatasourceTargetsDcrePayThroughThePayEnvironmentVariable() throws Exception {
        String url = String.valueOf(applicationYml().getProperty("spring.datasource.url"));
        assertThat(url)
                .as("PSX must take the PAY database URL variable, not the collections one")
                .startsWith("${DCRE_PAY_DB_URL:");
        assertThat(url)
                .as("the committed dev default must be dcre_pay")
                .contains("/dcre_pay?");
        assertThat(url)
                .as("dcre_col must appear nowhere: these readers create their own"
                        + " sbsr_resp in dcre_pay rather than reading collections' table")
                .doesNotContain("dcre_col");
    }

    /**
     * The exchange-root default is a RELATIVE path six ../ deep from a service
     * directory (payments/psx -> payments -> dcre -> spring -> java -> be -> repo,
     * landing on repo/infra/dcre-infra/exchange, verified out of band with
     * realpath). A miscount by one silently writes outcome seams into a directory
     * nothing watches, and the job still reports success.
     */
    @Test
    void theExchangeRootDefaultKeepsItsSixLevelDepth() throws Exception {
        String value = String.valueOf(applicationYml().getProperty("dcre.exchange-root"));
        String fallback = value.substring(value.indexOf(':') + 1, value.length() - 1);

        assertThat(fallback.split("\\.\\./", -1).length - 1)
                .as("six ../ from payments/psx reaches the repo root; five lands in be/,"
                        + " seven escapes the repo. Actual default: %s", fallback)
                .isEqualTo(6);
        assertThat(fallback)
                .as("and the tail must still be the infra exchange directory")
                .endsWith("infra/dcre-infra/exchange");
    }

    /** Reads the @Value placeholder key off the real constructor, not off a literal in this test. */
    private static String sliceSizePlaceholderKey() {
        Constructor<?> ctor = ReaderService.class.getDeclaredConstructors()[0];
        for (final Annotation[] onParameter : ctor.getParameterAnnotations()) {
            for (final Annotation annotation : onParameter) {
                if (annotation instanceof Value value) {
                    Matcher m = PLACEHOLDER.matcher(value.value());
                    if (m.find()) {
                        return m.group(1);
                    }
                }
            }
        }
        throw new AssertionError("ReaderService no longer declares a @Value placeholder;"
                + " this test can no longer see the binding it exists to guard");
    }

    private static PropertySource<?> applicationYml() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        assertThat(sources).as("application.yml must be on the classpath and parse").isNotEmpty();
        return sources.getFirst();
    }
}
