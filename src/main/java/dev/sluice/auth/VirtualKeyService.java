package dev.sluice.auth;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class VirtualKeyService {

    public static final String KEY_PREFIX = "sk-sluice-";

    private final VirtualKeyRepository repository;
    private final SecureRandom random = new SecureRandom();

    public VirtualKeyService(VirtualKeyRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public IssuedKey issue(UUID accountId, String name) {
        byte[] entropy = new byte[32];
        random.nextBytes(entropy);
        String secret = KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        // Displayed in listings so an operator can tell keys apart without the secret.
        String display = secret.substring(0, KEY_PREFIX.length() + 6);
        VirtualKey key = repository.insert(UUID.randomUUID(), accountId, hash(secret), display, name);
        return new IssuedKey(key, secret);
    }

    public Optional<VirtualKey> resolve(String presentedSecret) {
        if (presentedSecret == null || presentedSecret.isBlank()) {
            return Optional.empty();
        }
        return repository.findByHash(hash(presentedSecret)).filter(VirtualKey::isActive);
    }

    public List<VirtualKey> list(UUID accountId) {
        return repository.findByAccount(accountId);
    }

    @Transactional
    public boolean revoke(UUID keyId) {
        return repository.revoke(keyId) > 0;
    }

    static String hash(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
