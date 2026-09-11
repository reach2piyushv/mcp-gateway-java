package com.piyush.mcpgateway.ratelimit;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A per-client token bucket, same math as the classic algorithm: each client
 * gets a bucket that holds up to {@code capacity} tokens, refills continuously
 * at {@code refillPerSec}, and every request consumes one token.
 * <p>
 * Thread-safety is explicit here on purpose: a {@link ConcurrentHashMap} for
 * the bucket registry, plus a per-bucket {@code synchronized} block around the
 * read-modify-write refill/consume sequence. Spring MVC handles requests on a
 * thread pool, so two requests for the same client can race on the same
 * bucket without that guard.
 * <p>
 * In production this state would move to Redis (Bucket4j has a Redis-backed
 * implementation) so it's shared across gateway replicas behind a load
 * balancer, rather than each replica enforcing its own independent limit.
 */
@Component
public class TokenBucketRateLimiter {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public record Result(boolean allowed, int remaining, long retryAfterSec) {
    }

    public Result tryConsume(String clientId, int capacity, double refillPerSec) {
        Bucket bucket = buckets.computeIfAbsent(clientId, k -> new Bucket(capacity, refillPerSec));
        synchronized (bucket) {
            refill(bucket);
            if (bucket.tokens >= 1) {
                bucket.tokens -= 1;
                return new Result(true, (int) Math.floor(bucket.tokens), 0);
            }
            double deficit = 1 - bucket.tokens;
            long retryAfterSec = (long) Math.ceil(deficit / bucket.refillPerSec);
            return new Result(false, 0, retryAfterSec);
        }
    }

    private void refill(Bucket bucket) {
        long now = System.currentTimeMillis();
        double elapsedSec = (now - bucket.lastRefillMillis) / 1000.0;
        double refillAmount = elapsedSec * bucket.refillPerSec;
        if (refillAmount > 0) {
            bucket.tokens = Math.min(bucket.capacity, bucket.tokens + refillAmount);
            bucket.lastRefillMillis = now;
        }
    }

    private static final class Bucket {
        double tokens;
        final double capacity;
        final double refillPerSec;
        long lastRefillMillis;

        Bucket(double capacity, double refillPerSec) {
            this.tokens = capacity;
            this.capacity = capacity;
            this.refillPerSec = refillPerSec;
            this.lastRefillMillis = System.currentTimeMillis();
        }
    }
}
