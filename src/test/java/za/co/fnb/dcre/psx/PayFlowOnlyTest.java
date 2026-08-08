package za.co.fnb.dcre.psx;

import org.junit.jupiter.api.Test;
import za.co.fnb.dcre.psx.data.model.SbsrRespEntity;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PSX reads the PAYMENTS N/ACK reply leg ONLY, out of dcre_pay. The whole point
 * of the split is that payments stops sharing a reader and a database with
 * collections, so a flow discriminator in this service, or a read of a
 * collections-owned table, would mean the split had not happened.
 *
 * <p>Same shape as PRR's PayFlowOnlyTest, and the same review finding applies:
 * a scan for string literals is satisfied by DELETING the literals, not by
 * removing the concept. Each test below closes a different escape, and each has
 * been seen red on its own.
 */
class PayFlowOnlyTest {

    /** The literal scan. Cheap, catches constants and validators by name. */
    @Test
    void psxCarriesNoFlowDiscriminator() throws Exception {
        assertThat(offendingSources(s -> s.contains("FLOW_PAY") || s.contains("validatedFlow")
                || s.contains("\"COL\"")))
                .as("PSX serves one family. A flow branch here means collections logic was"
                        + " carried across instead of left behind")
                .isEmpty();
    }

    /**
     * The PERSISTED shape, read off the class rather than off its text. A field
     * named flow survives any amount of comment rewording, and it is the one that
     * would put the column back into the upsert.
     */
    @Test
    void verdictEntityDeclaresNoFlowField() {
        List<String> fields = Arrays.stream(SbsrRespEntity.class.getDeclaredFields())
                .map(Field::getName)
                .toList();
        assertThat(fields)
                .as("sbsr_resp in dcre_pay has no flow column, so the entity must not declare"
                        + " one: the database is the discriminator now")
                .doesNotContain("flow");
        // Control: this reflection CAN see the entity's fields, so the absence
        // above is a missing field and not an empty read of the wrong class.
        assertThat(fields)
                .as("control: the reflection reads real fields")
                .contains("responseFile", "e2e", "status");
        // The dropped collections correlation, asserted on the persisted shape
        // rather than on a comment: emission_id pointed at crw_emission, which
        // lives in dcre_col and this service can never read.
        assertThat(fields)
                .as("no emission correlation: crw_emission is a collections table in dcre_col")
                .doesNotContain("emissionId");
    }

    /**
     * The RESOURCES, which a java-only walk cannot reach. A flow column
     * reintroduced by a changeset, or a flow key wired through application.yml,
     * is exactly as much of a discriminator as a Java constant.
     */
    @Test
    void noResourceReintroducesAFlowColumnOrKey() throws Exception {
        assertThat(offendingResources(s -> s.contains("name=\"flow\"")
                || s.contains("columnName=\"flow\"") || s.contains("flow:") || s.contains("'flow'")))
                .as("no changeset may add a flow column and no config may carry a flow key:"
                        + " dcre_pay's sbsr_resp has no such column")
                .isEmpty();
    }

    /**
     * The LAUNCH surface. A flow job parameter AGT could still supply and this
     * service would silently ignore is the worst of both shapes.
     */
    @Test
    void noSourceReadsAFlowJobParameter() throws Exception {
        assertThat(offendingSources(s -> s.contains("get(\"flow\")")
                || s.contains("jobParameters['flow']") || s.contains("String flow")))
                .as("PSX takes no flow launch parameter: there is nothing left to"
                        + " discriminate on, so accepting one would be a lie")
                .isEmpty();
    }

    /**
     * The DATABASE boundary. Dropping the emission correlation is only real if no
     * SQL anywhere still names a collections-owned table; crw_emission does not
     * exist in dcre_pay, so a surviving query would fail at runtime, not at build.
     */
    @Test
    void nothingQueriesACollectionsOwnedTable() throws Exception {
        assertThat(offendingSources(s -> s.contains("crw_emission") || s.contains("dcre_col")))
                .as("database per family: PSX owns sbsr_resp in dcre_pay and reads nothing"
                        + " out of dcre_col")
                .isEmpty();
        assertThat(offendingResources(s -> s.contains("crw_emission") || s.contains("dcre_col")))
                .as("no changeset or config may reference collections' database or tables")
                .isEmpty();
    }

    private static List<Path> offendingSources(final java.util.function.Predicate<String> offends)
            throws Exception {
        return walk(Path.of("src/main/java"), p -> p.toString().endsWith(".java"), offends);
    }

    private static List<Path> offendingResources(final java.util.function.Predicate<String> offends)
            throws Exception {
        return walk(Path.of("src/main/resources"), Files::isRegularFile, offends);
    }

    private static List<Path> walk(final Path root, final java.util.function.Predicate<Path> include,
                                   final java.util.function.Predicate<String> offends) throws Exception {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).filter(include)
                    .filter(p -> {
                        try {
                            return offends.test(stripComments(Files.readString(p)));
                        } catch (Exception e) {
                            throw new IllegalStateException(p.toString(), e);
                        }
                    })
                    .toList();
        }
    }

    /**
     * Comments are stripped before scanning, and this is load-bearing rather than
     * tidiness. Explaining WHY a boundary exists means naming the thing on the
     * other side of it, so the changelog and the yml both say "dcre_col" in prose
     * and this suite went red on its own documentation the first time it ran.
     * Left uncorrected the pressure is to delete the explanation, which is exactly
     * backwards: what must not survive is a live reference, not a description of a
     * dead one.
     *
     * <p>Verified red-proof: reinstating {@code emission_id} in the repository's
     * upsert, and {@code /dcre_col?} in the datasource URL, each fails the
     * relevant test with the comments still in place.
     */
    static String stripComments(final String text) {
        return text
                .replaceAll("(?s)<!--.*?-->", "")   // xml
                .replaceAll("(?s)/\\*.*?\\*/", "")  // java block and javadoc
                .replaceAll("(?m)^\\s*//.*$", "")   // java line
                .replaceAll("(?m)^\\s*#.*$", "");   // yml
    }
}
