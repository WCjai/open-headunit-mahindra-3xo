/*
 * ChaCha20-Poly1305 AEAD (RFC 8439) for CarPlay's video and control streams.
 *
 * Open Headunit addition. Android 8 has no ChaCha20 in its platform crypto, so DiPlay fell back to
 * BouncyCastle's Java code, which decrypts at 1-4 MB/s on the head unit's Cortex-A7: 10-90 ms per
 * video frame. This plain C version is tens of times faster. Poly1305 follows poly1305-donna's
 * 32-bit form (26-bit limbs). The tag check is constant time.
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "chacha_poly.h"

static inline uint32_t load32(const uint8_t *p) {
    return (uint32_t) p[0] | ((uint32_t) p[1] << 8) | ((uint32_t) p[2] << 16) | ((uint32_t) p[3] << 24);
}

static inline void store32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t) v; p[1] = (uint8_t) (v >> 8); p[2] = (uint8_t) (v >> 16); p[3] = (uint8_t) (v >> 24);
}

/* --- ChaCha20 ----------------------------------------------------------------------------- */

#define ROTL(a, b) (((a) << (b)) | ((a) >> (32 - (b))))
#define QR(a, b, c, d) do { \
    a += b; d ^= a; d = ROTL(d, 16); \
    c += d; b ^= c; b = ROTL(b, 12); \
    a += b; d ^= a; d = ROTL(d, 8);  \
    c += d; b ^= c; b = ROTL(b, 7);  \
} while (0)

static void chacha_block(const uint32_t in[16], uint8_t out[64]) {
    uint32_t x0 = in[0], x1 = in[1], x2 = in[2], x3 = in[3], x4 = in[4], x5 = in[5], x6 = in[6], x7 = in[7];
    uint32_t x8 = in[8], x9 = in[9], x10 = in[10], x11 = in[11], x12 = in[12], x13 = in[13], x14 = in[14], x15 = in[15];
    for (int i = 0; i < 10; i++) {
        QR(x0, x4, x8, x12); QR(x1, x5, x9, x13); QR(x2, x6, x10, x14); QR(x3, x7, x11, x15);
        QR(x0, x5, x10, x15); QR(x1, x6, x11, x12); QR(x2, x7, x8, x13); QR(x3, x4, x9, x14);
    }
    store32(out + 0, x0 + in[0]);   store32(out + 4, x1 + in[1]);   store32(out + 8, x2 + in[2]);
    store32(out + 12, x3 + in[3]);  store32(out + 16, x4 + in[4]);  store32(out + 20, x5 + in[5]);
    store32(out + 24, x6 + in[6]);  store32(out + 28, x7 + in[7]);  store32(out + 32, x8 + in[8]);
    store32(out + 36, x9 + in[9]);  store32(out + 40, x10 + in[10]); store32(out + 44, x11 + in[11]);
    store32(out + 48, x12 + in[12]); store32(out + 52, x13 + in[13]); store32(out + 56, x14 + in[14]);
    store32(out + 60, x15 + in[15]);
}

static void chacha_init(uint32_t st[16], const uint8_t key[32], uint32_t counter, const uint8_t nonce[12]) {
    st[0] = 0x61707865; st[1] = 0x3320646e; st[2] = 0x79622d32; st[3] = 0x6b206574;
    for (int i = 0; i < 8; i++) st[4 + i] = load32(key + 4 * i);
    st[12] = counter;
    st[13] = load32(nonce); st[14] = load32(nonce + 4); st[15] = load32(nonce + 8);
}

static void chacha_xor(uint32_t st[16], const uint8_t *in, uint8_t *out, size_t len) {
    uint8_t block[64];
    while (len > 0) {
        chacha_block(st, block);
        st[12]++;
        size_t n = len < 64 ? len : 64;
        for (size_t i = 0; i < n; i++) out[i] = in[i] ^ block[i];
        in += n; out += n; len -= n;
    }
    memset(block, 0, sizeof block);
}

/* --- Poly1305 (26-bit limbs) --------------------------------------------------------------- */

typedef struct {
    uint32_t r0, r1, r2, r3, r4;
    uint32_t h0, h1, h2, h3, h4;
    uint32_t pad0, pad1, pad2, pad3;
} poly1305;

static void poly_init(poly1305 *p, const uint8_t key[32]) {
    p->r0 = load32(key + 0) & 0x3ffffff;
    p->r1 = (load32(key + 3) >> 2) & 0x3ffff03;
    p->r2 = (load32(key + 6) >> 4) & 0x3ffc0ff;
    p->r3 = (load32(key + 9) >> 6) & 0x3f03fff;
    p->r4 = (load32(key + 12) >> 8) & 0x00fffff;
    p->h0 = p->h1 = p->h2 = p->h3 = p->h4 = 0;
    p->pad0 = load32(key + 16); p->pad1 = load32(key + 20);
    p->pad2 = load32(key + 24); p->pad3 = load32(key + 28);
}

/* Whole 16-byte blocks only: the AEAD pads every part to 16 bytes, so there is no short final block. */
static void poly_blocks(poly1305 *p, const uint8_t *m, size_t len) {
    const uint32_t r0 = p->r0, r1 = p->r1, r2 = p->r2, r3 = p->r3, r4 = p->r4;
    const uint32_t s1 = r1 * 5, s2 = r2 * 5, s3 = r3 * 5, s4 = r4 * 5;
    uint32_t h0 = p->h0, h1 = p->h1, h2 = p->h2, h3 = p->h3, h4 = p->h4;
    while (len >= 16) {
        h0 += load32(m + 0) & 0x3ffffff;
        h1 += (load32(m + 3) >> 2) & 0x3ffffff;
        h2 += (load32(m + 6) >> 4) & 0x3ffffff;
        h3 += (load32(m + 9) >> 6) & 0x3ffffff;
        h4 += (load32(m + 12) >> 8) | (1u << 24);

        uint64_t d0 = (uint64_t) h0 * r0 + (uint64_t) h1 * s4 + (uint64_t) h2 * s3 + (uint64_t) h3 * s2 + (uint64_t) h4 * s1;
        uint64_t d1 = (uint64_t) h0 * r1 + (uint64_t) h1 * r0 + (uint64_t) h2 * s4 + (uint64_t) h3 * s3 + (uint64_t) h4 * s2;
        uint64_t d2 = (uint64_t) h0 * r2 + (uint64_t) h1 * r1 + (uint64_t) h2 * r0 + (uint64_t) h3 * s4 + (uint64_t) h4 * s3;
        uint64_t d3 = (uint64_t) h0 * r3 + (uint64_t) h1 * r2 + (uint64_t) h2 * r1 + (uint64_t) h3 * r0 + (uint64_t) h4 * s4;
        uint64_t d4 = (uint64_t) h0 * r4 + (uint64_t) h1 * r3 + (uint64_t) h2 * r2 + (uint64_t) h3 * r1 + (uint64_t) h4 * r0;

        uint32_t c;
        c = (uint32_t) (d0 >> 26); h0 = (uint32_t) d0 & 0x3ffffff;
        d1 += c; c = (uint32_t) (d1 >> 26); h1 = (uint32_t) d1 & 0x3ffffff;
        d2 += c; c = (uint32_t) (d2 >> 26); h2 = (uint32_t) d2 & 0x3ffffff;
        d3 += c; c = (uint32_t) (d3 >> 26); h3 = (uint32_t) d3 & 0x3ffffff;
        d4 += c; c = (uint32_t) (d4 >> 26); h4 = (uint32_t) d4 & 0x3ffffff;
        h0 += c * 5; c = h0 >> 26; h0 &= 0x3ffffff;
        h1 += c;

        m += 16;
        len -= 16;
    }
    p->h0 = h0; p->h1 = h1; p->h2 = h2; p->h3 = h3; p->h4 = h4;
}

/* Data padded with zeros to a multiple of 16 bytes, as the AEAD's MAC input is. */
static void poly_padded(poly1305 *p, const uint8_t *m, size_t len) {
    size_t whole = len & ~(size_t) 15;
    poly_blocks(p, m, whole);
    if (len > whole) {
        uint8_t block[16] = {0};
        memcpy(block, m + whole, len - whole);
        poly_blocks(p, block, 16);
    }
}

static void poly_finish(poly1305 *p, uint8_t mac[16]) {
    uint32_t h0 = p->h0, h1 = p->h1, h2 = p->h2, h3 = p->h3, h4 = p->h4, c;
    c = h1 >> 26; h1 &= 0x3ffffff;
    h2 += c; c = h2 >> 26; h2 &= 0x3ffffff;
    h3 += c; c = h3 >> 26; h3 &= 0x3ffffff;
    h4 += c; c = h4 >> 26; h4 &= 0x3ffffff;
    h0 += c * 5; c = h0 >> 26; h0 &= 0x3ffffff;
    h1 += c;

    /* h - p, and keep it when it does not go negative */
    uint32_t g0 = h0 + 5; c = g0 >> 26; g0 &= 0x3ffffff;
    uint32_t g1 = h1 + c; c = g1 >> 26; g1 &= 0x3ffffff;
    uint32_t g2 = h2 + c; c = g2 >> 26; g2 &= 0x3ffffff;
    uint32_t g3 = h3 + c; c = g3 >> 26; g3 &= 0x3ffffff;
    uint32_t g4 = h4 + c - (1u << 26);
    uint32_t mask = (g4 >> 31) - 1;
    g0 &= mask; g1 &= mask; g2 &= mask; g3 &= mask; g4 &= mask;
    mask = ~mask;
    h0 = (h0 & mask) | g0; h1 = (h1 & mask) | g1; h2 = (h2 & mask) | g2;
    h3 = (h3 & mask) | g3; h4 = (h4 & mask) | g4;

    /* to 4 x 32 bits, then + pad mod 2^128 */
    h0 = h0 | (h1 << 26);
    h1 = (h1 >> 6) | (h2 << 20);
    h2 = (h2 >> 12) | (h3 << 14);
    h3 = (h3 >> 18) | (h4 << 8);
    uint64_t f;
    f = (uint64_t) h0 + p->pad0; h0 = (uint32_t) f;
    f = (uint64_t) h1 + p->pad1 + (f >> 32); h1 = (uint32_t) f;
    f = (uint64_t) h2 + p->pad2 + (f >> 32); h2 = (uint32_t) f;
    f = (uint64_t) h3 + p->pad3 + (f >> 32); h3 = (uint32_t) f;
    store32(mac + 0, h0); store32(mac + 4, h1); store32(mac + 8, h2); store32(mac + 12, h3);
    memset(p, 0, sizeof *p);
}

/* --- AEAD ---------------------------------------------------------------------------------- */

static void aead_tag(const uint8_t key[32], const uint8_t nonce[12], const uint8_t *aad, size_t aad_len,
                     const uint8_t *ct, size_t ct_len, uint8_t tag[16]) {
    uint32_t st[16];
    uint8_t block0[64];
    chacha_init(st, key, 0, nonce);
    chacha_block(st, block0);
    poly1305 p;
    poly_init(&p, block0);
    poly_padded(&p, aad, aad_len);
    poly_padded(&p, ct, ct_len);
    uint8_t lengths[16];
    uint64_t a = aad_len, c = ct_len;
    for (int i = 0; i < 8; i++) { lengths[i] = (uint8_t) (a >> (8 * i)); lengths[8 + i] = (uint8_t) (c >> (8 * i)); }
    poly_blocks(&p, lengths, 16);
    poly_finish(&p, tag);
    memset(block0, 0, sizeof block0);
    memset(st, 0, sizeof st);
}

void chacha_poly_seal(const uint8_t key[32], const uint8_t nonce[12], const uint8_t *aad, size_t aad_len,
                      const uint8_t *pt, size_t pt_len, uint8_t *out /* pt_len + 16 */) {
    uint32_t st[16];
    chacha_init(st, key, 1, nonce);
    chacha_xor(st, pt, out, pt_len);
    aead_tag(key, nonce, aad, aad_len, out, pt_len, out + pt_len);
    memset(st, 0, sizeof st);
}

int chacha_poly_open(const uint8_t key[32], const uint8_t nonce[12], const uint8_t *aad, size_t aad_len,
                     const uint8_t *in, size_t in_len, uint8_t *out /* in_len - 16 */) {
    if (in_len < 16) return 0;
    size_t ct_len = in_len - 16;
    uint8_t tag[16];
    aead_tag(key, nonce, aad, aad_len, in, ct_len, tag);
    uint8_t diff = 0;
    for (int i = 0; i < 16; i++) diff |= (uint8_t) (tag[i] ^ in[ct_len + i]);
    if (diff != 0) return 0;
    uint32_t st[16];
    chacha_init(st, key, 1, nonce);
    chacha_xor(st, in, out, ct_len);
    memset(st, 0, sizeof st);
    return 1;
}

/* --- JNI: com.shilapi.xcertplay.airplay.NativeChacha -------------------------------------- */

/* The output, or null when the tag does not match (open) or an argument is malformed. */
static jbyteArray run(JNIEnv *env, jboolean seal, jbyteArray jkey, jbyteArray jnonce, jbyteArray jin, jbyteArray jaad) {
    if (jkey == NULL || jnonce == NULL || jin == NULL) return NULL;
    if ((*env)->GetArrayLength(env, jkey) != 32 || (*env)->GetArrayLength(env, jnonce) != 12) return NULL;
    jsize in_len = (*env)->GetArrayLength(env, jin);
    jsize aad_len = jaad == NULL ? 0 : (*env)->GetArrayLength(env, jaad);
    if (!seal && in_len < 16) return NULL;
    jsize out_len = seal ? in_len + 16 : in_len - 16;

    uint8_t key[32], nonce[12];
    (*env)->GetByteArrayRegion(env, jkey, 0, 32, (jbyte *) key);
    (*env)->GetByteArrayRegion(env, jnonce, 0, 12, (jbyte *) nonce);
    uint8_t aad_stack[64];
    uint8_t *aad = aad_stack;
    if (aad_len > (jsize) sizeof aad_stack) {
        aad = malloc((size_t) aad_len);
        if (aad == NULL) return NULL;
    }
    if (aad_len > 0) (*env)->GetByteArrayRegion(env, jaad, 0, aad_len, (jbyte *) aad);

    jbyteArray jout = (*env)->NewByteArray(env, out_len);
    if (jout == NULL) { if (aad != aad_stack) free(aad); return NULL; }

    int ok = 1;
    uint8_t *in = (*env)->GetPrimitiveArrayCritical(env, jin, NULL);
    uint8_t *out = in == NULL ? NULL : (*env)->GetPrimitiveArrayCritical(env, jout, NULL);
    if (in == NULL || out == NULL) {
        ok = 0;
    } else if (seal) {
        chacha_poly_seal(key, nonce, aad, (size_t) aad_len, in, (size_t) in_len, out);
    } else {
        ok = chacha_poly_open(key, nonce, aad, (size_t) aad_len, in, (size_t) in_len, out);
    }
    if (out != NULL) (*env)->ReleasePrimitiveArrayCritical(env, jout, out, ok ? 0 : JNI_ABORT);
    if (in != NULL) (*env)->ReleasePrimitiveArrayCritical(env, jin, in, JNI_ABORT);
    memset(key, 0, sizeof key);
    if (aad != aad_stack) free(aad);
    if (!ok) {
        (*env)->DeleteLocalRef(env, jout);
        return NULL;
    }
    return jout;
}

JNIEXPORT jbyteArray JNICALL
Java_com_shilapi_xcertplay_airplay_NativeChacha_nativeSeal(JNIEnv *env, jclass clazz, jbyteArray key, jbyteArray nonce,
                                                         jbyteArray plaintext, jbyteArray aad) {
    (void) clazz;
    return run(env, JNI_TRUE, key, nonce, plaintext, aad);
}

JNIEXPORT jbyteArray JNICALL
Java_com_shilapi_xcertplay_airplay_NativeChacha_nativeOpen(JNIEnv *env, jclass clazz, jbyteArray key, jbyteArray nonce,
                                                         jbyteArray ciphertext, jbyteArray aad) {
    (void) clazz;
    return run(env, JNI_FALSE, key, nonce, ciphertext, aad);
}
