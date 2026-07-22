# opentelemetry-trace-propagation

![CI](https://github.com/xMesas/opentelemetry-trace-propagation/actions/workflows/ci.yml/badge.svg)

## In plain English

`observable-order-service`, an earlier project in this portfolio, proved distributed
tracing works using Micrometer Tracing + Brave + Zipkin. That's a very common real
stack, but it isn't **OpenTelemetry** — the vendor-neutral standard increasingly
requested on its own. This project builds tracing directly on the **OpenTelemetry
SDK itself**, with no Java agent doing the instrumentation invisibly: every span is
created by hand, and the trace context crossing from one HTTP call to the next is
injected and extracted manually using the standard W3C `traceparent` mechanism.
Spans are exported over real OTLP to a real Jaeger backend, and the proof that
propagation actually worked comes from Jaeger's own query API - not a log line
that merely looks plausible.

## What actually got measured

Real run: started the app, pointed at a real `docker-compose`-launched Jaeger,
then:

```bash
curl -X POST localhost:8080/orders -H 'Content-Type: application/json' -d '{"item":"widget"}'
# {"item":"widget","inStock":true}

curl -X POST localhost:8080/orders -H 'Content-Type: application/json' -d '{"item":"unknown-thing"}'
# {"item":"unknown-thing","inStock":false}
```

Then queried Jaeger's own real trace-query API (`GET /api/traces?service=...`) and
got back, for the first request, **one trace ID
(`e6d6f3c1540c2cc7b2815ad41986ac01`) containing all three spans**, correctly
nested:

```json
"place-order"              (span.kind=server, root)
  -> "check-stock-client-call"   (span.kind=client, CHILD_OF place-order)
       -> "check-stock"          (span.kind=server, CHILD_OF check-stock-client-call)
```

All three spans share the exact same `traceID`, even though `check-stock-client-call`
and `check-stock` are on opposite ends of a genuinely separate HTTP request/response
(real loopback network hop, real headers) - proof that the manually-injected/extracted
W3C `traceparent` header is what's actually stitching the trace together, not
something implicit.

## One real bug found by actually running it

The very first version tried to build the internal HTTP client's base URL from a
`${local.server.port}` placeholder in `application.yml`, injected via `@Value` into
`InventoryClient`'s constructor. Startup failed immediately:

```
PlaceholderResolutionException: Could not resolve placeholder 'local.server.port'
in value "http://localhost:${local.server.port}"
```

The embedded Tomcat server (and the `local.server.port` property it publishes via
`WebServerInitializedEvent`) only starts **after** Spring finishes constructing all
singleton beans, not before - so a bean's constructor can never see that
placeholder resolved, whether the port is fixed or random. Fixed by having
`InventoryClient` implement `ApplicationListener<WebServerInitializedEvent>`
directly and build its `RestClient` once that event actually fires, instead of
relying on property-placeholder timing.

## Approach

- `OtelConfig` — wires the OpenTelemetry SDK by hand: a `Resource` naming this
  service, an `SdkTracerProvider` with a `BatchSpanProcessor` exporting via
  `OtlpGrpcSpanExporter`, and a `ContextPropagators` instance using the standard
  `W3CTraceContextPropagator`. No Java agent, no auto-instrumentation - every piece
  a real production setup would configure is visible here.
- `OrderController` (`POST /orders`) — starts the root span, then calls
  `InventoryClient`.
- `InventoryClient` — calls this same app's `/inventory/check` endpoint over a real
  loopback HTTP connection, manually injecting the current span context into the
  outgoing request headers via `TextMapPropagator.inject(...)`.
- `InventoryController` (`GET /inventory/check`) — manually extracts whatever trace
  context arrived in the incoming request's headers via `TextMapPropagator.extract(...)`
  and starts its own span as a child of that extracted context, continuing the same
  trace rather than starting a new one.

## Architecture decisions

- **Manual SDK wiring instead of a Java agent, on purpose.** The OTel Java agent
  auto-instruments Spring MVC/RestClient/etc. with zero application code, which is
  how OpenTelemetry is normally deployed in production - but it also hides the exact
  mechanism (span creation, context propagation via headers) this project exists to
  demonstrate understanding of. Manual instrumentation is strictly more work and
  not the production recommendation, but it's the right choice for a project whose
  point is proving the underlying mechanics are understood, not just configured.
- **No metrics/logs signal in this project - tracing only.** OpenTelemetry defines
  three signal types (traces, metrics, logs); adding metrics would need a second
  backend (Prometheus) and a second export pipeline, diluting the one clear claim
  this project makes. Scoped deliberately to tracing.
- **Jaeger's native OTLP receiver instead of a separate OpenTelemetry Collector.**
  Jaeger (`all-in-one`, `COLLECTOR_OTLP_ENABLED=true`) accepts OTLP directly since
  version 1.35+, so a Collector hop isn't needed to prove the core claim (real OTLP
  export, real cross-service trace-context propagation) - one less moving part.
- **The self-call-over-loopback-HTTP pattern**, already established elsewhere in
  this portfolio, stands in for two independent services without needing two
  separate deployables - the point (a real network hop, real header
  injection/extraction) doesn't require two different processes, just two
  independent HTTP request/response cycles.

## Stack

Java 21, Spring Boot 3.4.2, OpenTelemetry SDK 1.44.1 (`opentelemetry-api`,
`opentelemetry-sdk`, `opentelemetry-exporter-otlp`), Jaeger (`all-in-one:1.62.0`),
Testcontainers, Awaitility.

## Running it

```bash
docker compose up -d
./mvnw spring-boot:run
# in another shell:
curl -X POST localhost:8080/orders -H 'Content-Type: application/json' -d '{"item":"widget"}'
curl "http://localhost:16686/api/traces?service=opentelemetry-trace-propagation&limit=5"
# or open http://localhost:16686 for the Jaeger UI
```

**Note on Testcontainers locally**: this machine's Docker Desktop has a known
npipe-related incompatibility with the Testcontainers Java client (documented
elsewhere across this portfolio) - `OpenTelemetryTracePropagationIT` was validated
manually via `docker compose` + curl above instead, and the real Testcontainers run
happens in GitHub Actions CI.

## Status

- [x] Manual OpenTelemetry SDK instrumentation, no Java agent
- [x] Real OTLP export to a real Jaeger backend
- [x] Real cross-request trace-context propagation via the standard W3C header,
      verified via Jaeger's own query API (one trace ID, three correctly nested spans)
- [x] CI green with a real Testcontainers-backed Jaeger, verified by log
- [x] One real bug found and fixed by actually running the app

## Notes / next steps

- Tracing only - metrics and logs (OpenTelemetry's other two signal types) are not
  covered here.
- Two genuinely independent Spring Boot services (rather than one app self-calling
  itself) would make the "distributed" story even more literal, at the cost of a
  much heavier CI setup (two full app instances). The self-call pattern already
  proves the propagation mechanism works across a real network hop.
