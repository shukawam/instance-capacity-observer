# gpu-capacity-exporter

Exporter of the GPU(A.100, H.100) capacity.

## Build and run

build

```sh
./mvnw package
```

run

```sh
java -jar target/gpu-capacity-exporter.jar
```

(or Helidon CLI)

```sh
helidon dev
```

## Try capacity export

```sh
curl http://localhost:8080/capacity/report
```

Then, look at stdlog.

```sh
2024.01.09 17:53:28 INFO me.shukawam.InstanceCapacityReportResource VirtualThread[#32,[0x7de301ae 0x0195767c] WebServer socket]/runnable@ForkJoinPool-1-worker-1: This endpoint is only used for debug.
2024.01.09 17:53:30 INFO me.shukawam.InstanceCapacityReportService VirtualThread[#32,[0x7de301ae 0x0195767c] WebServer socket]/runnable@ForkJoinPool-1-worker-1: Generate compute capacity report.
2024.01.09 17:53:34 INFO me.shukawam.InstanceCapacityReportService VirtualThread[#32,[0x7de301ae 0x0195767c] WebServer socket]/runnable@ForkJoinPool-1-worker-1: BM.GPU.H100.8 TGjA:EU-FRANKFURT-1-AD-1 FAULT-DOMAIN-1 >> HARDWARE_NOT_SUPPORTED
2024.01.09 17:53:34 INFO me.shukawam.InstanceCapacityReportService VirtualThread[#32,[0x7de301ae 0x0195767c] WebServer socket]/runnable@ForkJoinPool-1-worker-1: BM.GPU.A100-v2.8 TGjA:EU-FRANKFURT-1-AD-1 FAULT-DOMAIN-1 >> HARDWARE_NOT_SUPPORTED
2024.01.09 17:53:34 INFO me.shukawam.InstanceCapacityReportService VirtualThread[#32,[0x7de301ae 0x0195767c] WebServer socket]/runnable@ForkJoinPool-1-worker-1: BM.GPU4.8 TGjA:EU-FRANKFURT-1-AD-1 FAULT-DOMAIN-1 >> HARDWARE_NOT_SUPPORTED
2024.01.09 17:53:35 INFO me.shukawam.InstanceCapacityReportService VirtualThread[#32,[0x7de301ae 0x0195767c] WebServer socket]/runnable@ForkJoinPool-1-worker-1: BM.GPU.H100.8 TGjA:EU-FRANKFURT-1-AD-1 FAULT-DOMAIN-2 >> HARDWARE_NOT_SUPPORTED
# ... omit ...
```

## Try metrics

```sh
curl http://localhost:8080/metrics | grep -i gpu
```

You can get `gpu_shape_status` metrics via prometheus format.

```sh
gpu_shape_status{availability_domain="TGjA:EU-FRANKFURT-1-AD-2",fault_domain="FAULT-DOMAIN-1",mp_scope="application",shape="BM.GPU.H100.8",status="HARDWARE_NOT_SUPPORTED",} 0.0
gpu_shape_status{availability_domain="TGjA:PHX-AD-1",fault_domain="FAULT-DOMAIN-1",mp_scope="application",shape="BM.GPU4.8",status="HARDWARE_NOT_SUPPORTED",} 0.0
gpu_shape_status{availability_domain="TGjA:EU-FRANKFURT-1-AD-1",fault_domain="FAULT-DOMAIN-2",mp_scope="application",shape="BM.GPU4.8",status="HARDWARE_NOT_SUPPORTED",} 0.0
gpu_shape_status{availability_domain="TGjA:PHX-AD-2",fault_domain="FAULT-DOMAIN-1",mp_scope="application",shape="BM.GPU.A100-v2.8",status="HARDWARE_NOT_SUPPORTED",} 0.0
# ... omit ...
```
