package za.co.fnb.dcre.psx.data.model;

import org.springframework.data.relational.core.mapping.Table;
import za.co.fnb.dcre.platform.persistence.BaseEntity;

/**
 * One reply verdict per original payment transaction (N/ACK response leg).
 *
 * <p>The table is {@code sbsr_resp} unprefixed: the sheets name it that in every
 * family and dcre_pay supplies the namespace. There is no emission_id here; the
 * batch that carried the e2e is registered in a COLLECTIONS table this service
 * cannot and must not read.
 */
@Table("sbsr_resp")
public class SbsrRespEntity extends BaseEntity {

    private String responseFile;
    private String orgnlMsgId;
    private String e2e;
    private String status;
    private String reason;

    public String getResponseFile() { return responseFile; }
    public String getOrgnlMsgId() { return orgnlMsgId; }
    public String getE2e() { return e2e; }
    public String getStatus() { return status; }
    public String getReason() { return reason; }
}
