package com.threeam.payment.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.payment.entity.PaymentItem;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// 패들(Merchant of Record) 실연동. 토스와 결제 시점이 반대라는 점이 이 구현의 핵심이다:
// 토스는 우리가 승인을 쏴야 돈이 움직이지만, 패들은 결제창에서 이미 결제가 끝난 뒤에
// 우리가 사후 확인만 한다. 그래서 confirm은 "승인"이 아니라 "실상태 조회"다.
//
// 완료 규약(PaymentGateway 참고)은 토스 구현과 동일하게 지킨다:
// - 2xx → 실상태 파싱해 정상 완료
// - 4xx → 확실한 거절/부재로 정상 완료
// - 5xx, 타임아웃, 파싱 불능 → 예외 완료(불명) → 재동기화에 맡긴다
@Slf4j
@Component
@ConditionalOnProperty(name = "payment.provider", havingValue = "paddle")
public class PaddleClient implements PaymentGateway {

    private final PaymentProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public PaddleClient(PaymentProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    // 결제창을 열기 전에 패들 쪽 거래를 만들어 그 id를 받아둔다. 이 id가 없으면 결제가
    // 끝난 뒤 그 결제를 다시 찾을 방법이 없다 — 유저가 결제하고 창을 바로 닫은 경우
    // 지급을 복구할 수단이 사라진다. custom_data의 order_id가 우리 주문과의 연결고리다.
    @Override
    public CompletableFuture<String> prepare(String orderId, PaymentItem item, int amount) {
        String priceId = properties.getPaddle().getPriceIds().get(item.name());
        if (priceId == null || priceId.isBlank()) {
            log.error("패들 가격 ID 미설정 item={} — payment.paddle.price-ids 확인 필요", item.name());
            return CompletableFuture.failedFuture(
                    new BusinessException(ErrorCode.PAYMENT_PRICE_NOT_CONFIGURED));
        }
        ObjectNode line = objectMapper.createObjectNode()
                .put("price_id", priceId)
                .put("quantity", 1);
        ObjectNode body = objectMapper.createObjectNode();
        body.set("items", objectMapper.createArrayNode().add(line));
        body.set("custom_data", objectMapper.createObjectNode().put("order_id", orderId));

        HttpRequest request = baseRequest("/transactions")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() / 100 != 2) {
                        // 여기 실패는 유저가 결제창을 보기도 전이다 — 돈이 안 걸려 있으니
                        // 주문 생성을 실패시키고 다시 시도하게 하는 편이 안전하다.
                        log.error("패들 거래 생성 실패 status={} body={}",
                                response.statusCode(), response.body());
                        throw new PaymentGatewayException("패들 거래 생성 실패: " + response.statusCode());
                    }
                    return readTree(response.body()).path("data").path("id").asText(null);
                });
    }

    // 패들엔 승인 API가 없다. 결제창이 닫힌 시점의 실상태를 조회해 그대로 반영한다.
    @Override
    public CompletableFuture<PgPaymentResult> confirm(String paymentKey, String orderId, int amount) {
        return fetchTransaction(paymentKey, orderId, amount);
    }

    // 환불은 거래 상태 변경이 아니라 조정(adjustment) 생성이다. 패들이 즉시 승인하지 않고
    // 검토 대기로 두는 경우가 있어, 그때는 확정하지 않고 재동기화가 결론을 내게 한다.
    @Override
    public CompletableFuture<PgPaymentResult> cancel(String paymentKey, int cancelAmount, String reason,
                                                     String idempotencyKey, RefundAccount refundAccount) {
        // 패들은 원결제 수단으로 되돌려주므로 환불 계좌를 받지 않는다(가상계좌가 없다).
        ObjectNode body = objectMapper.createObjectNode()
                .put("action", "refund")
                .put("transaction_id", paymentKey)
                .put("reason", reason)
                .put("type", "full");
        HttpRequest request = baseRequest("/adjustments")
                // 같은 키의 재전송을 패들이 최초 1회로 흡수한다 — 응답 유실 후 재시도가 이중 환불이 되지 않는다.
                .header("Paddle-Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    JsonNode root = readTree(response.body());
                    if (response.statusCode() / 100 == 2) {
                        return adjustmentResult(paymentKey, root.path("data"), cancelAmount);
                    }
                    if (response.statusCode() / 100 == 4) {
                        log.warn("패들 환불 거절 status={} body={}", response.statusCode(), response.body());
                        return new PgPaymentResult(paymentKey, null, PgStatus.FAILED, null, null,
                                errorMessage(root), 0, null);
                    }
                    throw new PaymentGatewayException("패들 응답 5xx: " + response.statusCode());
                });
    }

    // 웹훅과 재동기화의 진실 원천. 주문 생성 때 저장해 둔 거래 id로 조회한다 —
    // 패들엔 우리 orderId로 거래를 찾는 API가 없어서 id를 미리 잡아두는 것이다.
    @Override
    public CompletableFuture<PgPaymentResult> findByOrderId(String orderId, String paymentKey) {
        if (paymentKey == null || paymentKey.isBlank()) {
            // 거래 id가 저장돼 있지 않은 주문 — 조회할 방법이 없다. 부재(NOT_FOUND)로 단정하면
            // 만료 처리로 이어지는데, 패들에서는 거래가 만들어졌는데 저장만 실패한 경우 실제로
            // 결제됐을 수 있다. 아무것도 확정하지 않고 만료는 방치 주문 스케줄러에 맡긴다.
            return CompletableFuture.completedFuture(PgPaymentResult.of(null, orderId, PgStatus.UNKNOWN));
        }
        return fetchTransaction(paymentKey, orderId, 0);
    }

    // expectedAmount가 0이면 금액 검증을 건너뛴다(재동기화 경로 — 주문 금액을 들고 오지 않는다).
    private CompletableFuture<PgPaymentResult> fetchTransaction(String paymentKey, String orderId,
                                                                int expectedAmount) {
        HttpRequest request = baseRequest("/transactions/" + paymentKey).GET().build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    JsonNode root = readTree(response.body());
                    if (response.statusCode() == 404) {
                        return PgPaymentResult.of(paymentKey, orderId, PgStatus.NOT_FOUND);
                    }
                    if (response.statusCode() / 100 == 4) {
                        log.warn("패들 조회 거절 status={} body={}", response.statusCode(), response.body());
                        return new PgPaymentResult(paymentKey, orderId, PgStatus.FAILED, null, null,
                                errorMessage(root), 0, null);
                    }
                    if (response.statusCode() / 100 != 2) {
                        throw new PaymentGatewayException("패들 응답 5xx: " + response.statusCode());
                    }
                    return parse(root.path("data"), orderId, expectedAmount);
                });
    }

    private PgPaymentResult parse(JsonNode data, String orderId, int expectedAmount) {
        String paymentKey = data.path("id").asText(null);

        // 남의 거래 id를 들고 와도 우리 주문으로 지급되지 않게 하는 관문. 거래에 박아둔
        // order_id는 우리 서버만 넣을 수 있으므로 위조가 불가능하다.
        String linkedOrderId = data.path("custom_data").path("order_id").asText(null);
        if (orderId != null && linkedOrderId != null && !orderId.equals(linkedOrderId)) {
            log.error("패들 거래의 주문 불일치 txn={} 기대={} 실제={}", paymentKey, orderId, linkedOrderId);
            return PgPaymentResult.of(paymentKey, orderId, PgStatus.UNKNOWN);
        }

        JsonNode totals = data.path("details").path("totals");
        int grandTotal = parseAmount(totals.path("grand_total").asText(null));
        // 결제창에서 실제로 청구된 금액이 우리 주문 금액과 다르면 어느 쪽도 확정하지 않는다.
        // 정상 흐름엔 없는 조합이라(가격 고정, 수량 상한 1) 자동 판단 대신 사람이 보게 남긴다.
        if (expectedAmount > 0 && grandTotal > 0 && grandTotal != expectedAmount) {
            log.error("패들 거래 금액 불일치 txn={} 주문={} 청구={}", paymentKey, expectedAmount, grandTotal);
            return PgPaymentResult.of(paymentKey, orderId, PgStatus.UNKNOWN);
        }

        PgStatus status = mapStatus(data.path("status").asText(""));

        // 환불은 거래 상태를 바꾸지 않고 조정으로만 남는다 — 조정 후 총액이 줄었으면 취소로 읽는다.
        int refunded = 0;
        JsonNode adjusted = data.path("details").path("adjusted_totals");
        if (!adjusted.isMissingNode() && status == PgStatus.DONE) {
            int adjustedTotal = parseAmount(adjusted.path("grand_total").asText(null));
            if (grandTotal > 0 && adjustedTotal < grandTotal) {
                refunded = grandTotal - adjustedTotal;
                status = adjustedTotal == 0 ? PgStatus.CANCELED : PgStatus.PARTIAL_CANCELED;
            }
        }

        return new PgPaymentResult(paymentKey, orderId, status, method(data),
                parseTime(data.path("billed_at").asText(null)), null, refunded, null);
    }

    private PgPaymentResult adjustmentResult(String paymentKey, JsonNode data, int cancelAmount) {
        String adjustmentStatus = data.path("status").asText("");
        return switch (adjustmentStatus) {
            case "approved" -> new PgPaymentResult(paymentKey, null, PgStatus.CANCELED, null, null,
                    null, cancelAmount, null);
            case "rejected" -> new PgPaymentResult(paymentKey, null, PgStatus.FAILED, null, null,
                    "패들이 환불을 거절했습니다", 0, null);
            // 검토 대기 — 아직 돈이 돌아가지 않았다. 확정하지 않고 재동기화가 결론을 내게 둔다.
            default -> PgPaymentResult.of(paymentKey, null, PgStatus.IN_PROGRESS);
        };
    }

    private String method(JsonNode data) {
        JsonNode payments = data.path("payments");
        if (!payments.isArray() || payments.isEmpty()) {
            return null;
        }
        String type = payments.get(payments.size() - 1).path("method_details").path("type").asText("");
        return switch (type) {
            case "card", "south_korea_local_card" -> "카드";
            case "paypal" -> "페이팔";
            case "apple_pay" -> "애플페이";
            case "google_pay" -> "구글페이";
            case "kakao_pay" -> "카카오페이";
            case "naver_pay" -> "네이버페이";
            case "samsung_pay" -> "삼성페이";
            case "payco" -> "페이코";
            case "alipay" -> "알리페이";
            case "" -> null;
            default -> type;
        };
    }

    private PgStatus mapStatus(String paddleStatus) {
        return switch (paddleStatus) {
            case "draft", "ready" -> PgStatus.READY;
            // 청구는 됐으나 아직 수금 전 — 결론이 안 났으니 아무것도 확정하지 않는다.
            case "billed", "past_due" -> PgStatus.IN_PROGRESS;
            case "paid", "completed" -> PgStatus.DONE;
            // 결제 전에 무효화된 거래다. 돈이 움직인 적이 없으므로 환불이 아니라 만료로 읽는다.
            case "canceled" -> PgStatus.EXPIRED;
            default -> {
                log.warn("알 수 없는 패들 상태: {}", paddleStatus);
                yield PgStatus.UNKNOWN;
            }
        };
    }

    private HttpRequest.Builder baseRequest(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(properties.getPaddle().getBaseUrl() + path))
                .timeout(Duration.ofSeconds(properties.getPaddle().getTimeoutSeconds()))
                .header("Authorization", "Bearer " + properties.getPaddle().getApiKey())
                .header("Content-Type", "application/json");
    }

    // 패들 금액은 최소 통화 단위의 문자열이다. 원화는 소수 자릿수가 0이라 그대로 원이 된다.
    private int parseAmount(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.warn("패들 금액 파싱 실패: {}", value);
            return 0;
        }
    }

    private String errorMessage(JsonNode root) {
        JsonNode error = root.path("error");
        return error.path("code").asText("") + ": " + error.path("detail").asText("");
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
            throw new PaymentGatewayException("패들 응답 파싱 실패", e);
        }
    }
}
