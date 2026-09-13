package dev.sluice.auth;

/**
 * @param secret the only time the plaintext key exists. Sluice stores a SHA-256
 *               hash and cannot recover it.
 */
public record IssuedKey(VirtualKey key, String secret) {
}
