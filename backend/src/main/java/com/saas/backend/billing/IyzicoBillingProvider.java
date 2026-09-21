package com.saas.backend.billing;

import com.saas.backend.auth.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

@Component
public class IyzicoBillingProvider implements BillingProvider {
    private static final String CHECKOUT_PATH = "/v2/subscription/checkoutform/initialize";
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final SecureRandom random = new SecureRandom();
    private final String baseUrl, apiKey, secretKey, merchantId, planReference, callbackUrl;

    public IyzicoBillingProvider(ObjectMapper json,
            @Value("${app.billing.iyzico.base-url:https://sandbox-api.iyzipay.com}") String baseUrl,
            @Value("${app.billing.iyzico.api-key:}") String apiKey,
            @Value("${app.billing.iyzico.secret-key:}") String secretKey,
            @Value("${app.billing.iyzico.merchant-id:}") String merchantId,
            @Value("${app.billing.iyzico.plan-reference:}") String planReference,
            @Value("${app.billing.callback-url:http://localhost:8080/api/public/billing/iyzico/callback}") String callbackUrl) {
        this.json=json; this.baseUrl=trimSlash(baseUrl); this.apiKey=apiKey; this.secretKey=secretKey;
        this.merchantId=merchantId; this.planReference=planReference; this.callbackUrl=callbackUrl;
    }

    @Override public boolean configured() {
        return !apiKey.isBlank() && !secretKey.isBlank() && !merchantId.isBlank() && !planReference.isBlank()
                && callbackUrl.startsWith("https://");
    }

    @Override public Checkout initialize(String conversationId, Customer customer) {
        requireConfigured();
        var body = json.createObjectNode();
        body.put("locale","tr"); body.put("conversationId",conversationId); body.put("callbackUrl",callbackUrl);
        body.put("pricingPlanReferenceCode",planReference); body.put("subscriptionInitialStatus","ACTIVE");
        var buyer=body.putObject("customer"); buyer.put("name",customer.name()); buyer.put("surname",customer.surname());
        buyer.put("email",customer.email()); buyer.put("gsmNumber",customer.gsmNumber()); buyer.put("identityNumber",customer.identityNumber());
        var address=buyer.putObject("billingAddress"); address.put("address",customer.address());
        address.put("contactName",customer.name()+" "+customer.surname()); address.put("city",customer.city());
        address.put("country",customer.country()); if(customer.zipCode()!=null) address.put("zipCode",customer.zipCode());
        JsonNode response=request("POST",CHECKOUT_PATH,json.writeValueAsString(body));
        return new Checkout(required(response,"token"),required(response,"checkoutFormContent"),response.path("tokenExpireTime").asInt(1800));
    }

    @Override public CheckoutResult retrieve(String token, String conversationId) {
        requireConfigured();
        String path="/v2/subscription/checkoutform/"+pathPart(token)+"?conversationId="+pathPart(conversationId);
        JsonNode root=request("GET",path,""); JsonNode data=root.path("data");
        return new CheckoutResult(required(root,"conversationId"),required(data,"referenceCode"),required(data,"customerReferenceCode"),
                required(data,"pricingPlanReferenceCode"),required(data,"subscriptionStatus"),
                instant(data,"trialEndDate"),instant(data,"endDate"));
    }

    @Override public Plan plan() {
        requireConfigured();
        JsonNode data=request("GET","/v2/subscription/pricing-plans/"+pathPart(planReference),"").path("data");
        return new Plan(required(data,"referenceCode"),data.path("price").decimalValue(),required(data,"currencyCode"),
                data.path("trialPeriodDays").asInt(0),required(data,"paymentInterval"));
    }

    @Override public void cancel(String subscriptionReference) {
        requireConfigured();
        request("POST","/v2/subscription/subscriptions/"+pathPart(subscriptionReference)+"/cancel","{}");
    }

    @Override public boolean validWebhookSignature(BillingDtos.Webhook event, String actual) {
        if(actual==null || actual.isBlank() || secretKey.isBlank()) return false;
        String message=merchantId+secretKey+event.iyziEventType()+event.subscriptionReferenceCode()
                +event.orderReferenceCode()+event.customerReferenceCode();
        String expected=hmacHex(secretKey,message);
        return java.security.MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),actual.toLowerCase().getBytes(StandardCharsets.US_ASCII));
    }

    private JsonNode request(String method,String path,String body) {
        try {
            String randomKey=Long.toString(System.currentTimeMillis())+random.nextInt(1_000_000);
            String signature=hmacHex(secretKey,randomKey+URI.create(path).getPath()+body);
            String auth=Base64.getEncoder().encodeToString(("apiKey:"+apiKey+"&randomKey:"+randomKey+"&signature:"+signature).getBytes(StandardCharsets.UTF_8));
            var builder=HttpRequest.newBuilder(URI.create(baseUrl+path)).timeout(Duration.ofSeconds(15))
                    .header("Authorization","IYZWSv2 "+auth).header("x-iyzi-rnd",randomKey).header("Content-Type","application/json");
            HttpRequest request=method.equals("GET")?builder.GET().build():builder.method(method,HttpRequest.BodyPublishers.ofString(body)).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode result=json.readTree(response.body());
            if(response.statusCode()/100!=2 || !"success".equalsIgnoreCase(result.path("status").asString()))
                throw new ApiException("PAYMENT_PROVIDER_ERROR","Ödeme sağlayıcısı işlemi tamamlayamadı. Lütfen tekrar deneyin.",
                        Map.of("provider","IYZICO","providerCode",result.path("errorCode").asString("UNKNOWN")));
            return result;
        } catch(ApiException ex) { throw ex; }
        catch(Exception ex) { throw new ApiException("PAYMENT_PROVIDER_UNAVAILABLE","Ödeme sağlayıcısına ulaşılamadı. Lütfen tekrar deneyin."); }
    }

    private void requireConfigured() { if(!configured()) throw new ApiException("BILLING_NOT_CONFIGURED","Ödeme sistemi henüz kullanıma hazır değil."); }
    private static String required(JsonNode node,String name) {
        String value=node.path(name).asString(); if(value.isBlank()) throw new ApiException("PAYMENT_PROVIDER_ERROR","Ödeme sağlayıcısı eksik yanıt döndürdü."); return value;
    }
    private static Instant instant(JsonNode node,String name) { long value=node.path(name).asLong(0); return value>0?Instant.ofEpochMilli(value):null; }
    private static String pathPart(String value) { return java.net.URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20"); }
    private static String trimSlash(String value) { return value.endsWith("/")?value.substring(0,value.length()-1):value; }
    static String hmacHex(String secret,String value) {
        try { Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch(Exception ex) { throw new IllegalStateException("HMAC-SHA256 kullanılamıyor",ex); }
    }
}
