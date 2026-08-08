package za.co.fnb.dcre.psx.data.repo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * response_file must hold any name agt_ops can register: file_arrival
 * .physical_filename is VARCHAR(512), and a reader column narrower than that
 * accepts the arrival and then crashes on the insert. The dcre_pay table is born
 * at 512 (001-pay-sbsr-resp.xml) rather than widened by a later ALTER, so this
 * asserts the schema the createTable actually produced: a 200-char name must
 * round-trip byte-exact AND the replay guard UNIQUE (response_file, e2e), the
 * row's full business identity, must reject a raw duplicate.
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "dcre.exchange-root=build/test-exchange"})
class ResponseFileWidthIT {

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

    /** 200 chars, realistic reply-name shape (fails on the pre-widening VARCHAR(128)). */
    static final String LONG_NAME = "20260716_FNB_SBSR_%s_RESP.xml"
            .formatted("x".repeat(200 - "20260716_FNB_SBSR__RESP.xml".length()));

    @Autowired
    SbsrRespRepo repo;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void twoHundredCharResponseFileRoundTrips() {
        assertEquals(200, LONG_NAME.length(), "fixture must be exactly 200 chars");

        repo.upsert(LONG_NAME, "MSG-0200", "E2E-200", "ACSC", null);

        assertEquals(LONG_NAME, jdbc.queryForObject(
                        "SELECT response_file FROM sbsr_resp WHERE e2e='E2E-200'", String.class),
                "200-char response_file must round-trip byte-exact through sbsr_resp");
    }

    @Test
    void replayGuardUniqueSurvivesWidening() {
        repo.upsert(LONG_NAME, "MSG-0201", "E2E-201", "ACSC", null);
        repo.upsert(LONG_NAME, "MSG-0201", "E2E-201", "RJCT", "AC04");

        assertEquals(1, jdbc.queryForObject(
                        "SELECT count(*) FROM sbsr_resp WHERE response_file=? AND e2e='E2E-201'",
                        Integer.class, LONG_NAME),
                "replay stays a no-op via ON CONFLICT (response_file, e2e)");
        assertThrows(DuplicateKeyException.class, () -> jdbc.update(
                        "INSERT INTO sbsr_resp (id, response_file, orgnl_msg_id, e2e, status)"
                                + " VALUES (gen_random_uuid(), ?, 'MSG-0201', 'E2E-201', 'ACSC')", LONG_NAME),
                "UNIQUE (response_file, e2e) from 001-psx.xml still enforced after widening");
    }
}
