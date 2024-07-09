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
                        LOGGER.info(String.format("%s %s/%s >> %s {%s}", availability.getInstanceShape(), faultDomain,
                                availabilityDomain, availability.getAvailabilityStatus().getValue(),
                                availability.getAvailableCount()));
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
                                .compartmentId(compartmentId).shapeAvailabilities(new ArrayList<>(Arrays.asList(
                                        /**
                                         * 大規模スケール・アウトのAIトレーニング、データ分析、HPC
                                         */
                                        // BM H100 80GB x 8
                                        CreateCapacityReportShapeAvailabilityDetails.builder()
                                                .instanceShape("BM.GPU.H100.8").faultDomain(faultDomain)
                                                .instanceShapeConfig(CapacityReportInstanceShapeConfig.builder()
                                                        .ocpus(112.0f).memoryInGBs(2048.0f).build())
                                                .build(),
                                        // BM A100 80GB x 8
                                        CreateCapacityReportShapeAvailabilityDetails.builder()
                                                .instanceShape("BM.GPU4.8").faultDomain(faultDomain)
                                                .instanceShapeConfig(CapacityReportInstanceShapeConfig.builder()
                                                        .ocpus(64.0f).memoryInGBs(2048.0f).build())
                                                .build(),
                                        // BM A100 40GB x 8
                                        CreateCapacityReportShapeAvailabilityDetails.builder()
                                                .instanceShape("BM.GPU.A100-v2.8").faultDomain(faultDomain)
                                                .instanceShapeConfig(CapacityReportInstanceShapeConfig.builder()
                                                        .ocpus(128.0f).memoryInGBs(2048.0f).build())
                                                .build(),
                                        /**
                                         * 小規模AIトレーニング、推論、ストリーミング、ゲーム、仮想デスクトップ基盤
                                         */
                                        // VM A10 x 1
                                        CreateCapacityReportShapeAvailabilityDetails.builder()
                                                .instanceShape("VM.GPU.A10.1").faultDomain(faultDomain)
                                                .instanceShapeConfig(CapacityReportInstanceShapeConfig.builder()
                                                        .ocpus(15.0f).memoryInGBs(240.0f).build())
                                                .build(),
                                        // VM A10 x 2
                                        CreateCapacityReportShapeAvailabilityDetails.builder()
                                                .instanceShape("VM.GPU.A10.2").faultDomain(faultDomain)
                                                .instanceShapeConfig(CapacityReportInstanceShapeConfig.builder()
                                                        .ocpus(30.0f).memoryInGBs(480.0f).build())
                                                .build(),
                                        // BM A10 x 4
                                        CreateCapacityReportShapeAvailabilityDetails.builder()
                                                .instanceShape("BM.GPU.A10.4").faultDomain(faultDomain)
                                                .instanceShapeConfig(CapacityReportInstanceShapeConfig.builder()
                                                        .ocpus(64.0f).memoryInGBs(1024.0f).build())
                                                .build(),
                                        // VM V100 x 1
                                        CreateCapacityReportShapeAvailabilityDetails.builder()
                                                .instanceShape("VM.GPU3.1").faultDomain(faultDomain)
                                                .instanceShapeConfig(CapacityReportInstanceShapeConfig.builder()
                                                        .ocpus(6.0f).memoryInGBs(90.0f).build())
                                                .build(),
                                        // VM V100 x 2
                                        CreateCapacityReportShapeAvailabilityDetails.builder()
                                                .instanceShape("VM.GPU3.2").faultDomain(faultDomain)
                                                .instanceShapeConfig(CapacityReportInstanceShapeConfig.builder()
                                                        .ocpus(12.0f).memoryInGBs(180.0f).build())
                                                .build(),
                                        // VM V100 x 4
                                        CreateCapacityReportShapeAvailabilityDetails.builder()
                                                .instanceShape("VM.GPU3.4").faultDomain(faultDomain)
                                                .instanceShapeConfig(CapacityReportInstanceShapeConfig.builder()
                                                        .ocpus(24.0f).memoryInGBs(360.0f).build())
                                                .build(),
                                        // BM V100 x 8
                                        CreateCapacityReportShapeAvailabilityDetails.builder()
                                                .instanceShape("BM.GPU3.8").faultDomain(faultDomain)
                                                .instanceShapeConfig(CapacityReportInstanceShapeConfig.builder()
                                                        .ocpus(52.0f).memoryInGBs(768.0f).build())
                                                .build()
                                // VM P100 x 1
                                // CreateCapacityReportShapeAvailabilityDetails.builder()
                                // .instanceShape("VM.GPU2.1").faultDomain(faultDomain)
                                // .instanceShapeConfig(CapacityReportInstanceShapeConfig.builder()
                                // .ocpus(12.0f).memoryInGBs(72.0f).build())
                                // .build(),
                                // BM P100 x 2
                                // CreateCapacityReportShapeAvailabilityDetails.builder()
                                // .instanceShape("BM.GPU2.2").faultDomain(faultDomain)
                                // .instanceShapeConfig(CapacityReportInstanceShapeConfig.builder()
                                // .ocpus(28.0f).memoryInGBs(192.0f).build())
                                // .build()
                                ))).build()).build();
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
        if (count == null) {
            return Long.valueOf(0);
        } else {
            return count;
        }
    }
}
