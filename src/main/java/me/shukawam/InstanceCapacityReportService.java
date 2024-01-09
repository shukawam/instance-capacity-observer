package me.shukawam;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.Tag;

import com.oracle.bmc.auth.InstancePrincipalsAuthenticationDetailsProvider;
import com.oracle.bmc.core.ComputeClient;
import com.oracle.bmc.core.model.CapacityReportInstanceShapeConfig;
import com.oracle.bmc.core.model.CapacityReportShapeAvailability;
import com.oracle.bmc.core.model.ComputeCapacityReport;
import com.oracle.bmc.core.model.CreateCapacityReportShapeAvailabilityDetails;
import com.oracle.bmc.core.model.CreateComputeCapacityReportDetails;
import com.oracle.bmc.core.requests.CreateComputeCapacityReportRequest;
import com.oracle.bmc.identity.IdentityClient;
import com.oracle.bmc.identity.requests.ListAvailabilityDomainsRequest;
import com.oracle.bmc.identity.requests.ListFaultDomainsRequest;
import com.oracle.bmc.identity.requests.ListRegionSubscriptionsRequest;

import io.helidon.microprofile.scheduling.FixedRate;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class InstanceCapacityReportService {
    private static final Logger LOGGER = Logger.getLogger(InstanceCapacityReportService.class.getName());
    private final IdentityClient identityClient;
    private final ComputeClient computeClient;

    private final String tenancyId;
    private final String compartmentId;

    private final MetricRegistry metricRegistry;

    @Inject
    public InstanceCapacityReportService(MetricRegistry metricRegistry,
            @ConfigProperty(name = "oci.tenancyId") String tenancyId,
            @ConfigProperty(name = "oci.compartmentId") String compartmentId) {
        var provider = InstancePrincipalsAuthenticationDetailsProvider.builder().build();
        this.identityClient = IdentityClient.builder().build(provider);
        this.computeClient = ComputeClient.builder().build(provider);
        this.tenancyId = tenancyId;
        this.compartmentId = compartmentId;
        this.metricRegistry = metricRegistry;
    }

    @FixedRate(initialDelay = 5, value = 10, timeUnit = TimeUnit.MINUTES)
    @WithSpan(value = "generate_capacity_report_metrics")
    public void generateCapacityReportMetrics() {
        LOGGER.info("Generate compute capacity report.");
        var regions = listRegions();
        regions.forEach(region -> {
            initializeClient(region);
            var availabilityDomains = listAvailabilityDomains();
            availabilityDomains.forEach(availabilityDomain -> {
                LOGGER.fine(String.format("Availability Domain: %s", availabilityDomain));
                var faultDomains = listFaultDomains(availabilityDomain);
                faultDomains.forEach(faultDomain -> {
                    var capacityReport = createComputeCapacityReportResponse(availabilityDomain, faultDomain);
                    capacityReport.getShapeAvailabilities().forEach(availability -> {
                        var status = availability.getAvailabilityStatus().getValue();
                        LOGGER.info(String.format("%s %s %s >> %s", availability.getInstanceShape(), availabilityDomain,
                                faultDomain, status));
                        var tags = new ArrayList<Tag>();
                        tags.add(new Tag("availability_domain", availabilityDomain));
                        tags.add(new Tag("fault_domain", faultDomain));
                        tags.add(new Tag("shape", availability.getInstanceShape()));
                        tags.add(new Tag("status", availability.getAvailabilityStatus().getValue()));
                        metricRegistry.gauge("gpu_shape_status", () -> getAvailableCount(availability),
                                tags.toArray(new Tag[tags.size()]));
                    });
                });
            });
        });
    }

    private List<String> listRegions() {
        var regions = new ArrayList<String>();
        // Lists the region subscriptions for the specified tenancy.
        var listRegionSubscriptions = identityClient
                .listRegionSubscriptions(ListRegionSubscriptionsRequest.builder().tenancyId(tenancyId).build());
        if (listRegionSubscriptions.get__httpStatusCode__() != 200) {
            LOGGER.info(String.format("Status code: %s", listRegionSubscriptions.get__httpStatusCode__()));
            throw new ServiceException("Something wrong.");
        }
        listRegionSubscriptions.getItems().forEach(region -> {
            regions.add(region.getRegionName());
        });
        return regions;
    }

    private List<String> listAvailabilityDomains() {
        var availabilityDomains = new ArrayList<String>();
        var response = identityClient
                .listAvailabilityDomains(ListAvailabilityDomainsRequest.builder().compartmentId(compartmentId).build());
        if (response.get__httpStatusCode__() != 200) {
            LOGGER.info(String.format("Status code: %s", response.get__httpStatusCode__()));
            throw new ServiceException("Something wrong.");
        }
        response.getItems().forEach(availabilityDomain -> {
            LOGGER.fine(availabilityDomain.getName());
            availabilityDomains.add(availabilityDomain.getName());
        });
        return availabilityDomains;
    }

    private List<String> listFaultDomains(String ad) {
        var faultDomains = new ArrayList<String>();
        var response = identityClient.listFaultDomains(
                ListFaultDomainsRequest.builder().compartmentId(compartmentId).availabilityDomain(ad).build());
        if (response.get__httpStatusCode__() != 200) {
            LOGGER.info(String.format("Status code: %s", response.get__httpStatusCode__()));
            throw new ServiceException("Something wrong.");
        }
        response.getItems().forEach(fd -> {
            LOGGER.fine(String.format("FD: %s", fd.getName()));
            faultDomains.add(fd.getName());
        });
        return faultDomains;
    }

    private ComputeCapacityReport createComputeCapacityReportResponse(String availabilityDomain, String faultDomain) {
        var request = CreateComputeCapacityReportRequest.builder()
                .createComputeCapacityReportDetails(
                        CreateComputeCapacityReportDetails
                                .builder().availabilityDomain(
                                        availabilityDomain)
                                .compartmentId(compartmentId)
                                .shapeAvailabilities(new ArrayList<>(Arrays.asList(
                                        CreateCapacityReportShapeAvailabilityDetails.builder()
                                                .instanceShape("BM.GPU.H100.8").faultDomain(faultDomain)
                                                .instanceShapeConfig(CapacityReportInstanceShapeConfig.builder()
                                                        .ocpus(112.0f).memoryInGBs(2048.0f).build())
                                                .build(),
                                        CreateCapacityReportShapeAvailabilityDetails.builder()
                                                .instanceShape("BM.GPU.A100-v2.8").faultDomain(faultDomain)
                                                .instanceShapeConfig(CapacityReportInstanceShapeConfig.builder()
                                                        .ocpus(128.0f).memoryInGBs(2048.0f).build())
                                                .build(),
                                        CreateCapacityReportShapeAvailabilityDetails.builder()
                                                .instanceShape("BM.GPU4.8").faultDomain(faultDomain)
                                                .instanceShapeConfig(CapacityReportInstanceShapeConfig.builder()
                                                        .ocpus(64.0f).memoryInGBs(2048.0f).build())
                                                .build())))
                                .build())
                .build();
        var createComputeCapacityReportResponse = computeClient.createComputeCapacityReport(request);
        if (createComputeCapacityReportResponse.get__httpStatusCode__() != 200) {
            LOGGER.info(String.format("Status code: %s", createComputeCapacityReportResponse.get__httpStatusCode__()));
            throw new ServiceException("Something wrong.");
        }
        return createComputeCapacityReportResponse.getComputeCapacityReport();
    }

    private void initializeClient(String region) {
        identityClient.setRegion(region);
        computeClient.setRegion(region);
    }

    // TODO: Always return 0, because getAvailabilityCount is always return null.
    private Long getAvailableCount(CapacityReportShapeAvailability availability) {
        var count = availability.getAvailableCount();
        LOGGER.info(String.format("Available count: %s", count));
        if (count == null) {
            return Long.valueOf(0);
        } else {
            return count;
        }
    }
}
