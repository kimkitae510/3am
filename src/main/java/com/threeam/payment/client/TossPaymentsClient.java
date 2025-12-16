package com.threeam.payment.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// 토스페이먼츠 실연동. LLM 클라이언트와 같은 원칙 — java.net.http sendAsync 논블로킹,
// 느린 외부 호출이 서블릿 스레드와 DB 커넥션을 점유하지 않게 한다.
//
// 완료 규약(PaymentGateway 참고)을 여기서 구현한다:
// - 2xx → 실상태 파싱해 정상 완료
// - 4xx → "확실한 거절"도 앎이다. FAILED(또는 조회 404 → NOT_FOUND)로 정상 완료
// - 5xx, 타임아웃, 파싱 불능 → 예외 완료(불명) → 호출부가 재동기화로 넘긴다
@Slf4j
@Component
@ConditionalOnProperty(name = "payment.provider", havingValue = "toss")
public class TossPaymentsClient implements PaymentGateway {

    private final PaymentProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public TossPaymentsClient(PaymentProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public CompletableFuture<PgPaymentResult> confirm(String paymentKey, String orderId, int amount) {
        ObjectNode body = objectMapper.createObjectNode()
                .put("paymentKey", paymentKey)
                .put("orderId", orderId)
                .put("amount", amount);
        HttpRequest request = baseRequest("/payments/confirm")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        // 승인 4xx = 카드 한도, 잔액 부족 등 확실한 거절. 사유를 남기고 FAILED로 확정한다.
        return send(request, node -> rejected(paymentKey, orderId, node));
    }

    @Override
    public CompletableFuture<PgPaymentResult> cancel(String paymentKey, int cancelAmount, String reason,
                                                     String idempotencyKey, RefundAccount refundAccount) {
        ObjectNode body = objectMapper.createObjectNode()
                .put("cancelReason", reason)
                .put("cancelAmount", cancelAmount);
        if (refundAccount != null) {
            body.set("refundReceiveAccount", objectMapper.createObjectNode()
                    .put("bank", refundAccount.bank())
                    .put("accountNumber", refundAccount.accountNumber())
                    .put("holderName", refundAccount.holderName()));
        }
        HttpRequest request = baseRequest("/payments/" + paymentKey + "/cancel")
                // 같은 키의 재시도를 토스가 최초 1회로 흡수한다 — 응답 유실 후 재시도가 이중 환불이 되지 않는다.
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return send(request, node -> rejected(paymentKey, null, node));
    }

    @Override
    public CompletableFuture<PgPaymentResult> findByOrderId(String orderId) {
        HttpRequest request = baseRequest("/payments/orders/" + orderId)
                .GET()
                .build();
        // 조회 404 = 토스에 이 주문이 없다(위젯까지 못 감). 거절이 아니라 부재로 구분한다.
        return send(request, node -> "NOT_FOUND_PAYMENT".equals(node.path("code").asText())
                ? PgPaymentResult.of(null, orderId, PgStatus.NOT_FOUND)
                : rejected(null, orderId, node));
    }

    private HttpRequest.Builder baseRequest(String path) {
        String basic = Base64.getEncoder()
                .encodeToString((properties.getToss().getSecretKey() + ":").getBytes(StandardCharsets.UTF_8));
        return HttpRequest.newBuilder()
                .uri(URI.create(properties.getToss().getBaseUrl() + path))
                .timeout(Duration.ofSeconds(properties.getToss().getTimeoutSeconds()))
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/json");
    }

    private CompletableFuture<PgPaymentResult> send(HttpRequest request,
                                                    Function<JsonNode, PgPaymentResult> on4xx) {
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    JsonNode body = readTree(response.body());
                    if (response.statusCode() / 100 == 2) {
                        return parse(body);
                    }
                    if (response.statusCode() / 100 == 4) {
                        log.warn("토스 거절 응답 status={} body={}", response.statusCode(), response.body());
                        return on4xx.apply(body);
                    }
                    // 5xx는 토스 쪽 장애 — 실제 처리 여부를 모른다. 불명으로 던져 재동기화에 맡긴다.
                    throw new PaymentGatewayException("토스 응답 5xx: " + response.statusCode());
                });
    }

    private PgPaymentResult rejected(String paymentKey, String orderId, JsonNode errorBody) {
        String code = errorBody.path("code").asText("");
        String message = errorBody.path("message").asText("");
        return new PgPaymentResult(paymentKey, orderId, PgStatus.FAILED, null, null,
                code + ": " + message, 0, null);
    }

    private PgPaymentResult parse(JsonNode node) {
        int totalAmount = node.path("totalAmount").asInt(0);
        int balanceAmount = node.path("balanceAmount").asInt(totalAmount);
        PgPaymentResult.VirtualAccount virtualAccount = null;
        if (node.hasNonNull("virtualAccount")) {
            JsonNode va = node.get("virtualAccount");
            virtualAccount = new PgPaymentResult.VirtualAccount(
                    va.path("bankCode").asText(null),
                    va.path("accountNumber").asText(null),
                    parseTime(va.path("dueDate").asText(null)));
        }
        String failReason = null;
        if (node.hasNonNull("failure")) {
            failReason = node.path("failure").path("code").asText("")
                    + ": " + node.path("failure").path("message").asText("");
        }
        return new PgPaymentResult(
                node.path("paymentKey").asText(null),
                node.path("orderId").asText(null),
                mapStatus(node.path("status").asText("")),
                node.path("method").asText(null),
                parseTime(node.path("approvedAt").asText(null)),
                failReason,
                Math.max(0, totalAmount - balanceAmount),
                virtualAccount);
    }

    private PgStatus mapStatus(String tossStatus) {
        return switch (tossStatus) {
            case "READY" -> PgStatus.READY;
            case "IN_PROGRESS" -> PgStatus.IN_PROGRESS;
            case "WAITING_FOR_DEPOSIT" -> PgStatus.WAITING_FOR_DEPOSIT;
            case "DONE" -> PgStatus.DONE;
            case "CANCELED" -> PgStatus.CANCELED;
            case "PARTIAL_CANCELED" -> PgStatus.PARTIAL_CANCELED;
            case "ABORTED" -> PgStatus.FAILED;
            case "EXPIRED" -> PgStatus.EXPIRED;
            default -> {
                log.warn("알 수 없는 토스 상태: {}", tossStatus);
                yield PgStatus.UNKNOWN;
            }
        };
    }

    private LocalDateTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toLocalDateTime();
        } catch (RuntimeException e) {
            // 시각은 부가 정보 — 파싱 실패로 상태 반영 전체를 불명으로 만들지 않는다.
            return LocalDateTime.now();
        }
    }

    private JsonNode readTree(String body) {
        try {
            return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new PaymentGatewayException("토스 응답 파싱 실패", e);
        }
    }
}
