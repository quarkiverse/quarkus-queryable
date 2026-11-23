package io.quarkiverse.queryable.example.service.rs;

import java.util.List;

import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/test/audit")
@Produces(MediaType.APPLICATION_JSON)
public class AuditResource {

    @Inject
    AuditSink auditSink;

    @GET
    public List<String> events() {
        return auditSink.events();
    }

    @DELETE
    public void clear() {
        auditSink.clear();
    }
}
