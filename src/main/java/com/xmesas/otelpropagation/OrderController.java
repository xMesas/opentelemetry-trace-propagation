package com.xmesas.otelpropagation;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {

    public record OrderRequest(String item) {
    }

    public record OrderResult(String item, boolean inStock) {
    }

    private final Tracer tracer;
    private final InventoryClient inventoryClient;

    public OrderController(Tracer tracer, InventoryClient inventoryClient) {
        this.tracer = tracer;
        this.inventoryClient = inventoryClient;
    }

    @PostMapping("/orders")
    public OrderResult placeOrder(@RequestBody OrderRequest request) {
        Span span = tracer.spanBuilder("place-order").setSpanKind(SpanKind.SERVER).startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("order.item", request.item());
            boolean inStock = inventoryClient.checkStock(request.item());
            span.setAttribute("order.in_stock", inStock);
            return new OrderResult(request.item(), inStock);
        } finally {
            span.end();
        }
    }
}
