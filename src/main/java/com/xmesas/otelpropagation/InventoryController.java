package com.xmesas.otelpropagation;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Set;

/**
 * Simulates a second, independent service. It has no idea who called it - it only knows to look
 * for a standard W3C "traceparent" header and, if present, continue that trace instead of
 * starting a new one. That's the entire mechanism real distributed tracing across service
 * boundaries relies on, made explicit here instead of hidden inside an auto-instrumentation agent.
 */
@RestController
public class InventoryController {

    private static final Set<String> KNOWN_ITEMS = Set.of("widget", "gadget");

    private static final TextMapGetter<HttpServletRequest> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(HttpServletRequest carrier) {
            return Collections.list(carrier.getHeaderNames());
        }

        @Override
        public String get(HttpServletRequest carrier, String key) {
            return carrier == null ? null : carrier.getHeader(key);
        }
    };

    private final Tracer tracer;
    private final TextMapPropagator propagator;

    public InventoryController(OpenTelemetry openTelemetry, Tracer tracer) {
        this.tracer = tracer;
        this.propagator = openTelemetry.getPropagators().getTextMapPropagator();
    }

    @GetMapping("/inventory/check")
    public InventoryResponse check(@RequestParam String item, HttpServletRequest request) {
        Context extractedContext = propagator.extract(Context.root(), request, GETTER);

        Span span = tracer.spanBuilder("check-stock")
                .setParent(extractedContext)
                .setSpanKind(SpanKind.SERVER)
                .startSpan();
        try (Scope scope = span.makeCurrent()) {
            boolean inStock = KNOWN_ITEMS.contains(item);
            span.setAttribute("inventory.item", item);
            span.setAttribute("inventory.in_stock", inStock);
            return new InventoryResponse(item, inStock);
        } finally {
            span.end();
        }
    }
}
