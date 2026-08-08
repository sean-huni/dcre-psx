package za.co.fnb.dcre.psx.data.repo;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import za.co.fnb.dcre.psx.data.model.SbsrRespEntity;

import java.util.UUID;

public interface SbsrRespRepo extends CrudRepository<SbsrRespEntity, UUID> {

    // CRDB: UPSERT resolves on PK only, so replay safety needs
    // INSERT ... ON CONFLICT on the FULL business identity of the row,
    // (response_file, e2e). Both dimensions are load-bearing: e2e alone would let
    // a later reply file's verdict overwrite an earlier file's and still report
    // success, which is the narrow-key failure this project has already paid for
    // twice. The constraint backing this clause is uq_pay_sbsr_resp_file_e2e.
    @Modifying
    @Query("""
            INSERT INTO sbsr_resp (id, response_file, orgnl_msg_id, e2e, status, reason)
            VALUES (gen_random_uuid(), :responseFile, :orgnlMsgId, :e2e, :status, :reason)
            ON CONFLICT (response_file, e2e)
            DO UPDATE SET status = excluded.status, reason = excluded.reason,
                          updated_at = now()""")
    void upsert(@Param("responseFile") String responseFile, @Param("orgnlMsgId") String orgnlMsgId,
                @Param("e2e") String e2e, @Param("status") String status,
                @Param("reason") String reason);

    long countByResponseFile(String responseFile);
}
