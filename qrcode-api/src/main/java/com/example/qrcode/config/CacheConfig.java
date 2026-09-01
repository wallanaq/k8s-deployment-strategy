package com.example.qrcode.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/**
 * Enables Spring's caching abstraction. The actual cache (Caffeine,
 * in-process) is configured entirely via spring.cache.* properties in
 * application.yaml -- no CacheManager bean here, so TTL/size stay
 * externalized and configurable per-environment rather than hardcoded.
 *
 * In-memory, per-Pod cache: not shared across replicas. Accepted
 * trade-off for now (no new infrastructure like Redis), not an oversight.
 */
@Configuration
@EnableCaching
public class CacheConfig {
}
