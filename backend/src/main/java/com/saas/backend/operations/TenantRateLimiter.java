package com.saas.backend.operations;
import io.github.bucket4j.*;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.codec.ByteArrayCodec;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

@Service
public class TenantRateLimiter {
    private final RedisClient client;
    private StatefulRedisConnection<String,byte[]> connection;
    private ProxyManager<String> manager;
    public TenantRateLimiter(@Value("${app.redis-url:redis://localhost:6379}") String url) {
        var uri=io.lettuce.core.RedisURI.create(url); uri.setTimeout(Duration.ofSeconds(2)); client=RedisClient.create(uri);
    }
    private synchronized ProxyManager<String> manager() {
        if(manager==null) {
            connection=client.connect(RedisCodec.of(StringCodec.UTF8,ByteArrayCodec.INSTANCE));
            manager=Bucket4jLettuce.casBasedBuilder(connection)
                .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(2))).build();
        }
        return manager;
    }
    public ConsumptionProbe consume(UUID tenant, boolean pro) {
        int capacity=pro?600:120; // işletme (ekip) başına dakikalık ortak kota
        var config=BucketConfiguration.builder().addLimit(b -> b.capacity(capacity).refillIntervally(capacity,Duration.ofMinutes(1))).build();
        return manager().getProxy("saas:rate:"+tenant+":"+capacity,()->config).tryConsumeAndReturnRemaining(1);
    }
    public ConsumptionProbe consumeBooking(UUID tenant, String address) {
        var config=BucketConfiguration.builder().addLimit(b -> b.capacity(20).refillIntervally(20,Duration.ofHours(1))).build(); // paylaşılan Wi-Fi/CGNAT için gevşek; asıl sınır hesap başına
        var fingerprint=UUID.nameUUIDFromBytes((address==null?"unknown":address).getBytes(StandardCharsets.UTF_8));
        return manager().getProxy("saas:booking-ip:" + tenant + ":" + fingerprint,()->config).tryConsumeAndReturnRemaining(1);
    }

    /** Müşteri hesabı başına saatlik rezervasyon denemesi (IP'den bağımsız, asıl kötüye kullanım freni). */
    public ConsumptionProbe consumeBookingAccount(UUID tenant, UUID account) {
        var config=BucketConfiguration.builder().addLimit(b -> b.capacity(6).refillIntervally(6,Duration.ofHours(1))).build();
        return manager().getProxy("saas:booking-account:"+tenant+":"+account,()->config).tryConsumeAndReturnRemaining(1);
    }

        public ConsumptionProbe consumeAuth(String scope, String address) {
        int capacity = scope.endsWith("forgot") || scope.endsWith("reset") ? 10 : 20;
        var config = BucketConfiguration.builder()
            .addLimit(limit -> limit.capacity(capacity).refillIntervally(capacity, Duration.ofMinutes(1)))
            .build();
        var fingerprint = UUID.nameUUIDFromBytes((address == null ? "unknown" : address)
            .getBytes(StandardCharsets.UTF_8));
        return manager().getProxy("saas:auth-ip:" + scope + ":" + fingerprint, () -> config)
            .tryConsumeAndReturnRemaining(1);
        }

        public ConsumptionProbe consumeAuthAccount(String scope, String account) {
        var config = BucketConfiguration.builder()
            .addLimit(limit -> limit.capacity(10).refillIntervally(10, Duration.ofMinutes(1)))
            .build();
        var fingerprint = UUID.nameUUIDFromBytes((account == null ? "unknown" : account)
            .getBytes(StandardCharsets.UTF_8));
        return manager().getProxy("saas:auth-account:" + scope + ":" + fingerprint, () -> config)
            .tryConsumeAndReturnRemaining(1);
        }
    @PreDestroy public void close() {if(connection!=null) connection.close();client.shutdown();}
}
