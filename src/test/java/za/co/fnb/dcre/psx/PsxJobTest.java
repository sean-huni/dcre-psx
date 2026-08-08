package za.co.fnb.dcre.psx;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest(properties = {"spring.batch.job.enabled=false", "dcre.exchange-root=build/test-exchange"})
class PsxJobTest {

    static final CockroachContainer CRDB =
            new CockroachContainer(DockerImageName.parse("cockroachdb/cockroach:v26.2.3"));

    static {
        CRDB.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CRDB::getJdbcUrl);
        registry.add("spring.datasource.username", CRDB::getUsername);
        registry.add("spring.datasource.password", CRDB::getPassword);
    }

    @TempDir
    static Path dir;

    @Autowired
    Job psxJob;

    @Autowired
    JobOperator jobOperator;

    @Autowired
    JdbcTemplate jdbc;

    // [SYNTHETIC-CONTRACT R-35] reply shape
    static final String REPLY = """
            <Document>
              <OrgnlMsgId>MSG-0001</OrgnlMsgId>
              <Tx><OrgnlEndToEndId>E2E-1</OrgnlEndToEndId><TxSts>ACSC</TxSts></Tx>
              <Tx><OrgnlEndToEndId>E2E-2</OrgnlEndToEndId><TxSts>ACSC</TxSts></Tx>
              <Tx><OrgnlEndToEndId>E2E-3</OrgnlEndToEndId><TxSts>RJCT</TxSts><Rsn>AC04</Rsn></Tx>
              <Tx><OrgnlEndToEndId>E2E-4</OrgnlEndToEndId><TxSts>ACSC</TxSts></Tx>
            </Document>
            """;

    @Test
    void ingestsReplyFileAndReplayIsNoOp() throws Exception {
        wipeOutcomes();
        String original = "20260712_FNB_SBSR_reply.xml";
        Path input = dir.resolve(original);
        Files.writeString(input, REPLY);

        JobExecution run = jobOperator.start(psxJob, new JobParametersBuilder()
                .addString("arrival.id", UUID.randomUUID().toString(), true)
                .addString("input.file", input.toString(), false)
                .addString("original.name", original, false)
                .toJobParameters());
        assertEquals(BatchStatus.COMPLETED, run.getStatus());
        // SCRUM-58: no JOB_NAME env -> self-describing local seam name (shared OutcomeSeamListener)
        assertEquals(List.of("BUSINESS_ACCEPTED"),
                Files.readAllLines(Path.of("build/test-exchange", "outcomes", "local-psx-" + run.getId())),
                "seam outcome must land at outcomes/local-psx-<executionId>");
        assertEquals(4, jdbc.queryForObject(
                "SELECT count(*) FROM sbsr_resp WHERE response_file=?", Integer.class, original));
        assertEquals("RJCT", jdbc.queryForObject(
                "SELECT status FROM sbsr_resp WHERE response_file=? AND e2e='E2E-3'", String.class, original));
        assertEquals("AC04", jdbc.queryForObject(
                "SELECT reason FROM sbsr_resp WHERE response_file=? AND e2e='E2E-3'", String.class, original));
        assertEquals("ACSC", jdbc.queryForObject(
                "SELECT status FROM sbsr_resp WHERE response_file=? AND e2e='E2E-1'", String.class, original));
        assertNull(jdbc.queryForObject(
                "SELECT reason FROM sbsr_resp WHERE response_file=? AND e2e='E2E-1'", String.class, original));
        assertEquals("MSG-0001", jdbc.queryForObject(
                "SELECT orgnl_msg_id FROM sbsr_resp WHERE response_file=? AND e2e='E2E-1'", String.class, original));

        JobExecution replay = jobOperator.start(psxJob, new JobParametersBuilder()
                .addString("arrival.id", UUID.randomUUID().toString(), true)
                .addString("input.file", input.toString(), false)
                .addString("original.name", original, false)
                .toJobParameters());
        assertEquals(BatchStatus.COMPLETED, replay.getStatus());
        assertEquals(4, jdbc.queryForObject(
                "SELECT count(*) FROM sbsr_resp WHERE response_file=?", Integer.class, original),
                "replay is a no-op via ON CONFLICT (response_file, e2e)");
    }

    /** Stale seam files from earlier runs must not satisfy this run's assertion. */
    private static void wipeOutcomes() throws Exception {
        Path outcomes = Path.of("build/test-exchange", "outcomes");
        if (!Files.isDirectory(outcomes)) {
            return;
        }
        try (var files = Files.list(outcomes)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
    }
}
