/* ChaCha20-Poly1305 AEAD (RFC 8439). Open Headunit addition; see chacha_poly.c. */
#ifndef CHACHA_POLY_H
#define CHACHA_POLY_H

#include <stddef.h>
#include <stdint.h>

/* Writes pt_len + 16 bytes: the ciphertext, then the tag. */
void chacha_poly_seal(const uint8_t key[32], const uint8_t nonce[12], const uint8_t *aad, size_t aad_len,
                      const uint8_t *pt, size_t pt_len, uint8_t *out);

/* Writes in_len - 16 bytes of plaintext and returns 1, or returns 0 (writing nothing) when the tag is wrong. */
int chacha_poly_open(const uint8_t key[32], const uint8_t nonce[12], const uint8_t *aad, size_t aad_len,
                     const uint8_t *in, size_t in_len, uint8_t *out);

#endif
