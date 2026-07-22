package com.xmesas.otelpropagation;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Deliberately no Java agent here - the OTel SDK is wired up directly so the actual mechanics
 * (a Resource identifying this service, a SdkTracerProvider batching+exporting spans over OTLP,
 * and a W3C-standard ContextPropagators instance for cross-process trace-context propagation)
 * are visible in application code rather than hidden behind auto-instrumentation.
 */
@Configuration
public class OtelConfig {

    @Bean(destroyMethod = "close")
    public OpenTelemetrySdk openTelemetrySdk(@Value("${otel.service-name}") String serviceName,
                                              @Value("${otel.exporter.otlp.endpoint}") String otlpEndpoint) {
        Resource resource = Resource.getDefault().toBuilder()
                .put(AttributeKey.stringKey("service.name"), serviceName)
                .build();

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .addSpanProcessor(BatchSpanProcessor.builder(
                        OtlpGrpcSpanExporter.builder()
                                .setEndpoint(otlpEndpoint)
                                .build()
                ).build())
                .build();

        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(
                        io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator.getInstance()))
                .build();
    }

    @Bean
    public Tracer tracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer("com.xmesas.otelpropagation");
    }
}
