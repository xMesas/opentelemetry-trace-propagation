package com.xmesas.otelpropagation;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapPropagator;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

/**
 * Calls this same app's OTHER endpoint over a real loopback HTTP connection (the self-call
 * pattern already proven safe elsewhere in this portfolio) - genuinely separate request/response
 * cycle, real headers, real network stack, even though both sides happen to run in one process.
 * The current span context is manually injected into the outgoing request headers via the
 * standard W3C TextMapPropagator - nothing here is Spring- or OTel-agent-specific.
 *
 * <p>The RestClient's base URL can't be built eagerly in the constructor: this app's own listening
 * port (fixed for a normal run, random in the RANDOM_PORT integration test) is only known once the
 * embedded web server actually starts, which happens AFTER all singleton beans - including this one
 * - are already constructed (found by actually running it: a `${local.server.port}` placeholder
 * fails to resolve at startup with a PlaceholderResolutionException). Listening for
 * WebServerInitializedEvent instead correctly captures the real port at the moment it becomes
 * available, regardless of whether it was fixed or randomly assigned.
 */
@Component
public class InventoryClient implements ApplicationListener<WebServerInitializedEvent> {

    private final TextMapPropagator propagator;
    private final Tracer tracer;
    private volatile RestClient restClient;

    public InventoryClient(OpenTelemetry openTelemetry, Tracer tracer) {
        this.propagator = openTelemetry.getPropagators().getTextMapPropagator();
        this.tracer = tracer;
    }

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        int port = event.getWebServer().getPort();
        this.restClient = RestClient.create("http://localhost:" + port);
    }

    public boolean checkStock(String item) {
        Span span = tracer.spanBuilder("check-stock-client-call").setSpanKind(SpanKind.CLIENT).startSpan();
        try (Scope scope = span.makeCurrent()) {
            Map<String, String> headers = new HashMap<>();
            propagator.inject(Context.current(), headers, Map::put);

            InventoryResponse response = restClient.get()
                    .uri("/inventory/check?item={item}", item)
                    .headers(httpHeaders -> headers.forEach(httpHeaders::add))
                    .retrieve()
                    .body(InventoryResponse.class);

            return response != null && response.inStock();
        } finally {
            span.end();
        }
    }
}
