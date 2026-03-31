package benchmark.filter;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.logging.Level;
import java.util.logging.Logger;

@Provider
public class RecoveryFilter implements ExceptionMapper<Throwable> {

    private static final Logger LOG = Logger.getLogger(RecoveryFilter.class.getName());

    @Override
    public Response toResponse(Throwable exception) {
        StringWriter sw = new StringWriter();
        exception.printStackTrace(new PrintWriter(sw));
        LOG.log(Level.SEVERE, "Unhandled exception: " + sw);
        return Response.status(500)
                .entity("{\"error\":\"Internal Server Error\"}")
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
