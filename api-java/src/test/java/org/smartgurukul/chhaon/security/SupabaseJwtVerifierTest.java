package org.smartgurukul.chhaon.security;

import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Supabase login tokens: ES256 against the project's published public keys (current projects) and HS256 with a shared secret (older ones). */
class SupabaseJwtVerifierTest {

    private static final String USER = "11111111-2222-3333-4444-555555555555";

    private HttpServer server;
    private final AtomicInteger jwksCalls = new AtomicInteger();
    private volatile String jwksBody = "{\"keys\":[]}";
    private volatile int jwksStatus = 200;
    private String jwksUrl;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", ex -> {
            jwksCalls.incrementAndGet();
            byte[] b = jwksBody.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(jwksStatus, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        jwksUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static KeyPair ecKeys() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    private static String b64(java.math.BigInteger n) {
        byte[] raw = n.toByteArray();
        if (raw.length > 32) raw = java.util.Arrays.copyOfRange(raw, raw.length - 32, raw.length);
        byte[] padded = new byte[32];
        System.arraycopy(raw, 0, padded, 32 - raw.length, raw.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(padded);
    }

    private static String jwk(KeyPair k, String kid) {
        ECPublicKey p = (ECPublicKey) k.getPublic();
        return "{\"kty\":\"EC\",\"alg\":\"ES256\",\"use\":\"sig\",\"crv\":\"P-256\",\"kid\":\"" + kid + "\",\"x\":\"" + b64(p.getW().getAffineX()) + "\",\"y\":\"" + b64(p.getW().getAffineY()) + "\"}";
    }

    private static String es256(KeyPair k, String kid, String role, String sub, long expiresInMs) {
        var b = Jwts.builder().header().keyId(kid).and().claim("role", role).expiration(new Date(System.currentTimeMillis() + expiresInMs));
        if (sub != null) b.subject(sub);
        return b.signWith(k.getPrivate(), Jwts.SIG.ES256).compact();
    }

    @Test
    void aValidEs256TokenIsAccepted() throws Exception {
        KeyPair k = ecKeys();
        jwksBody = "{\"keys\":[" + jwk(k, "key-1") + "]}";
        var v = new SupabaseJwtVerifier("", jwksUrl);
        var claims = v.tryParse(es256(k, "key-1", "authenticated", USER, 60_000));
        assertThat(claims).isNotNull();
        assertThat(claims.getSubject()).isEqualTo(USER);
    }

    @Test
    void aTokenSignedByAnotherKeyIsRefused() throws Exception {
        KeyPair published = ecKeys(), attacker = ecKeys();
        jwksBody = "{\"keys\":[" + jwk(published, "key-1") + "]}";
        var v = new SupabaseJwtVerifier("", jwksUrl);
        assertThat(v.tryParse(es256(attacker, "key-1", "authenticated", USER, 60_000))).isNull();
    }

    @Test
    void anExpiredTokenIsRefused() throws Exception {
        KeyPair k = ecKeys();
        jwksBody = "{\"keys\":[" + jwk(k, "key-1") + "]}";
        var v = new SupabaseJwtVerifier("", jwksUrl);
        assertThat(v.tryParse(es256(k, "key-1", "authenticated", USER, -60_000))).isNull();
    }

    @Test
    void theAnonTokenAndATokenWithoutASubjectAreNotAPerson() throws Exception {
        KeyPair k = ecKeys();
        jwksBody = "{\"keys\":[" + jwk(k, "key-1") + "]}";
        var v = new SupabaseJwtVerifier("", jwksUrl);
        assertThat(v.tryParse(es256(k, "key-1", "anon", USER, 60_000))).isNull();
        assertThat(v.tryParse(es256(k, "key-1", "authenticated", null, 60_000))).isNull();
    }

    @Test
    void anUnknownKeyIdIsRefusedAndTheKeyServerIsNotHammered() throws Exception {
        KeyPair k = ecKeys(), rotated = ecKeys();
        jwksBody = "{\"keys\":[" + jwk(k, "key-1") + "]}";
        var v = new SupabaseJwtVerifier("", jwksUrl);
        assertThat(v.tryParse(es256(k, "key-1", "authenticated", USER, 60_000))).isNotNull();
        assertThat(jwksCalls.get()).isEqualTo(1);
        // a token with an unknown kid: refused, and the key server is asked at most once a minute
        assertThat(v.tryParse(es256(rotated, "key-2", "authenticated", USER, 60_000))).isNull();
        assertThat(v.tryParse(es256(rotated, "key-2", "authenticated", USER, 60_000))).isNull();
        assertThat(jwksCalls.get()).isEqualTo(1);
    }

    @Test
    void aRotatedKeyIsPickedUpByAVerifierThatLoadsTheNewList() throws Exception {
        KeyPair rotated = ecKeys();
        jwksBody = "{\"keys\":[" + jwk(rotated, "key-2") + "]}";
        var v = new SupabaseJwtVerifier("", jwksUrl);
        assertThat(v.tryParse(es256(rotated, "key-2", "authenticated", USER, 60_000))).isNotNull();
    }

    @Test
    void whenTheKeyServerIsDownTokensAreRefusedNotAccepted() throws Exception {
        KeyPair k = ecKeys();
        jwksStatus = 500;
        var v = new SupabaseJwtVerifier("", jwksUrl);
        assertThat(v.tryParse(es256(k, "key-1", "authenticated", USER, 60_000))).isNull();
    }

    @Test
    void hs256WithTheSharedSecretStillWorks() {
        String secret = "a-shared-secret-of-at-least-32-characters-long-0123";
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        var v = new SupabaseJwtVerifier(secret, "");
        String ok = Jwts.builder().subject(USER).claim("role", "authenticated").expiration(new Date(System.currentTimeMillis() + 60_000)).signWith(key, Jwts.SIG.HS256).compact();
        assertThat(v.tryParse(ok)).isNotNull();
        String anon = Jwts.builder().claim("role", "anon").expiration(new Date(System.currentTimeMillis() + 60_000)).signWith(key, Jwts.SIG.HS256).compact();
        assertThat(v.tryParse(anon)).isNull();
    }

    @Test
    void anHs256TokenIsNeverCheckedAgainstAPublicKey_andNothingIsAcceptedWhenNothingIsConfigured() throws Exception {
        KeyPair k = ecKeys();
        jwksBody = "{\"keys\":[" + jwk(k, "key-1") + "]}";
        var onlyJwks = new SupabaseJwtVerifier("", jwksUrl);
        SecretKey someKey = Keys.hmacShaKeyFor("another-secret-of-at-least-32-characters-long-xyz".getBytes(StandardCharsets.UTF_8));
        String hs = Jwts.builder().header().keyId("key-1").and().subject(USER).claim("role", "authenticated")
                .expiration(new Date(System.currentTimeMillis() + 60_000)).signWith(someKey, Jwts.SIG.HS256).compact();
        assertThat(onlyJwks.tryParse(hs)).isNull();

        var none = new SupabaseJwtVerifier("", "");
        assertThat(none.isConfigured()).isFalse();
        assertThat(none.tryParse(es256(k, "key-1", "authenticated", USER, 60_000))).isNull();
    }

    @Test
    void garbageIsRefused() {
        var v = new SupabaseJwtVerifier("", jwksUrl);
        assertThat(v.tryParse("not-a-token")).isNull();
        assertThat(v.tryParse("")).isNull();
    }
}
