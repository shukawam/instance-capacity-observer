package me.shukawam;

import java.util.logging.Logger;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/capacity")
public class InstanceCapacityReportResource {
    private static final Logger LOGGER = Logger.getLogger(InstanceCapacityReportResource.class.getName());
    private final InstanceCapacityReportService instanceCapacityReportService;

    @Inject
    public InstanceCapacityReportResource(InstanceCapacityReportService instanceCapacityReportService) {
        this.instanceCapacityReportService = instanceCapacityReportService;
    }

    @Path("/report")
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String report() {
        LOGGER.info("This endpoint is only used for debug.");
        instanceCapacityReportService.generateCapacityReportMetrics();
        return "ok";
    }
}
