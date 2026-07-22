package com.xmesas.otelpropagation;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * A real Jaeger container is the whole point here - it's the only backend genuinely capable of
 * proving "these two spans, produced by two separate HTTP-handled requests, belong to the same
 * trace" via its own query API, the same way a real production trace-explorer would.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OpenTelemetryTracePropagationIT {

    @Container
    static GenericContainer<?> jaeger = new GenericContainer<>(DockerImageName.parse("jaegertracing/all-in-one:1.62.0"))
            .withExposedPorts(16686, 4317)
            .withEnv("COLLECTOR_OTLP_ENABLED", "true");

    @DynamicPropertySource
    static void otelProps(DynamicPropertyRegistry registry) {
        registry.add("otel.exporter.otlp.endpoint",
                () -> "http://" + jaeger.getHost() + ":" + jaeger.getMappedPort(4317));
    }

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    OpenTelemetrySdk openTelemetrySdk;

    @Test
    void placingAnOrderProducesOneTraceWithThreeNestedSpansAcrossTheRealHttpHop() throws Exception {
        ResponseEntity<OrderController.OrderResult> response = restTemplate.postForEntity(
                "/orders", new OrderController.OrderRequest("widget"), OrderController.OrderResult.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().inStock()).isTrue();

        // Spans are batched and exported asynchronously - flush explicitly instead of guessing a sleep.
        openTelemetrySdk.getSdkTracerProvider().forceFlush().join(10, TimeUnit.SECONDS);

        String jaegerQueryUrl = "http://" + jaeger.getHost() + ":" + jaeger.getMappedPort(16686)
                + "/api/traces?service=opentelemetry-trace-propagation&limit=5";

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Map<?, ?> body = new RestTemplate().getForObject(jaegerQueryUrl, Map.class);
            List<?> traces = (List<?>) body.get("data");
            assertThat(traces).hasSize(1);

            Map<?, ?> trace = (Map<?, ?>) traces.get(0);
            List<?> spans = (List<?>) trace.get("spans");
            // place-order (root, server) -> check-stock-client-call (client) -> check-stock (server)
            assertThat(spans).hasSize(3);
        });
    }
}
