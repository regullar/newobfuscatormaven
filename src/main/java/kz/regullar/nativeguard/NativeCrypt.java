package kz.regullar.nativeguard;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class NativeCrypt {
    public static String meow(byte[] key, byte[] in) {
        try {
            int len = in.length;
            byte[] msg = new byte[] {
                    (byte)(len >>> 24), (byte)(len >>> 16),
                    (byte)(len >>> 8),  (byte)(len)
            };
            int h0 = 0x6a09e667, h1 = 0xbb67ae85, h2 = 0x3c6ef372, h3 = 0xa54ff53a;
            int h4 = 0x510e527f, h5 = 0x9b05688c, h6 = 0x1f83d9ab, h7 = 0x5be0cd19;
            int[] K = {
                    0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
                    0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
                    0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
                    0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
                    0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
                    0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
                    0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
                    0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
            };
            byte[] block = new byte[64];
            System.arraycopy(msg, 0, block, 0, 4);
            block[4] = (byte)0x80;
            long bitLen = 4L * 8;
            block[63] = (byte)(bitLen       );
            block[62] = (byte)(bitLen >>>  8);
            block[61] = (byte)(bitLen >>> 16);
            block[60] = (byte)(bitLen >>> 24);
            int[] W = new int[64];
            for (int t = 0; t < 16; t++) {
                W[t] = ((block[t*4]&0xFF)<<24) | ((block[t*4+1]&0xFF)<<16) |
                        ((block[t*4+2]&0xFF)<<8) | (block[t*4+3]&0xFF);
            }
            for (int t = 16; t < 64; t++) {
                int s0 = Integer.rotateRight(W[t-15],7) ^ Integer.rotateRight(W[t-15],18) ^ (W[t-15] >>> 3);
                int s1 = Integer.rotateRight(W[t-2],17) ^ Integer.rotateRight(W[t-2],19) ^ (W[t-2] >>> 10);
                W[t] = W[t-16] + s0 + W[t-7] + s1;
            }
            int a=h0, b=h1, c=h2, d=h3, e=h4, f=h5, g=h6, h=h7;
            for (int t = 0; t < 64; t++) {
                int S1 = Integer.rotateRight(e,6) ^ Integer.rotateRight(e,11) ^ Integer.rotateRight(e,25);
                int ch = (e & f) ^ (~e & g);
                int temp1 = h + S1 + ch + K[t] + W[t];
                int S0 = Integer.rotateRight(a,2) ^ Integer.rotateRight(a,13) ^ Integer.rotateRight(a,22);
                int maj = (a & b) ^ (a & c) ^ (b & c);
                int temp2 = S0 + maj;
                h = g; g = f; f = e; e = d + temp1;
                d = c; c = b; b = a; a = temp1 + temp2;
            }
            h0 += a; h1 += b; h2 += c; h3 += d;
            h4 += e; h5 += f; h6 += g; h7 += h;
            byte[] hash = new byte[32];
            int[] H = {h0,h1,h2,h3,h4,h5,h6,h7};
            for (int i = 0; i < 8; i++) {
                hash[i*4  ] = (byte)(H[i] >>> 24);
                hash[i*4+1] = (byte)(H[i] >>> 16);
                hash[i*4+2] = (byte)(H[i] >>> 8);
                hash[i*4+3] = (byte)(H[i]);
            }

            byte[] nonce = Arrays.copyOf(hash, 12);

            int[] state = new int[16];
            state[0] = 0x61707865; state[1] = 0x3320646e;
            state[2] = 0x79622d32; state[3] = 0x6b206574;
            for (int i = 0; i < 8; i++) {
                state[4+i] = ((key[i*4]&0xFF))        |
                        ((key[i*4+1]&0xFF)<<8 ) |
                        ((key[i*4+2]&0xFF)<<16) |
                        ((key[i*4+3]&0xFF)<<24);
            }
            state[12] = 0;
            for (int i = 0; i < 3; i++) {
                state[13+i] = ((nonce[i*4]&0xFF))        |
                        ((nonce[i*4+1]&0xFF)<<8 ) |
                        ((nonce[i*4+2]&0xFF)<<16) |
                        ((nonce[i*4+3]&0xFF)<<24);
            }

            byte[] out = new byte[in.length];
            byte[] blockS = new byte[64];
            int remaining = in.length, offset = 0;

            while (remaining > 0) {
                int[] x = Arrays.copyOf(state, 16);
                for (int r = 0; r < 10; r++) {
                    x[0] += x[4];  x[12] ^= x[0];  x[12] = Integer.rotateLeft(x[12],16);
                    x[8] += x[12]; x[4] ^= x[8];   x[4] = Integer.rotateLeft(x[4],12);
                    x[0] += x[4];  x[12] ^= x[0];  x[12] = Integer.rotateLeft(x[12],8);
                    x[8] += x[12]; x[4] ^= x[8];   x[4] = Integer.rotateLeft(x[4],7);

                    x[1] += x[5];  x[13] ^= x[1];  x[13] = Integer.rotateLeft(x[13],16);
                    x[9] += x[13]; x[5] ^= x[9];   x[5] = Integer.rotateLeft(x[5],12);
                    x[1] += x[5];  x[13] ^= x[1];  x[13] = Integer.rotateLeft(x[13],8);
                    x[9] += x[13]; x[5] ^= x[9];   x[5] = Integer.rotateLeft(x[5],7);

                    x[2] += x[6];  x[14] ^= x[2];  x[14] = Integer.rotateLeft(x[14],16);
                    x[10]+= x[14]; x[6] ^= x[10];  x[6] = Integer.rotateLeft(x[6],12);
                    x[2] += x[6];  x[14] ^= x[2];  x[14] = Integer.rotateLeft(x[14],8);
                    x[10]+= x[14]; x[6] ^= x[10];  x[6] = Integer.rotateLeft(x[6],7);

                    x[3] += x[7];  x[15] ^= x[3];  x[15] = Integer.rotateLeft(x[15],16);
                    x[11]+= x[15]; x[7] ^= x[11];  x[7] = Integer.rotateLeft(x[7],12);
                    x[3] += x[7];  x[15] ^= x[3];  x[15] = Integer.rotateLeft(x[15],8);
                    x[11]+= x[15]; x[7] ^= x[11];  x[7] = Integer.rotateLeft(x[7],7);

                    x[0] += x[5];  x[15]^= x[0];  x[15]=Integer.rotateLeft(x[15],16);
                    x[10]+= x[15]; x[5] ^= x[10]; x[5] =Integer.rotateLeft(x[5],12);
                    x[0] += x[5];  x[15]^= x[0];  x[15]=Integer.rotateLeft(x[15],8);
                    x[10]+= x[15]; x[5] ^= x[10]; x[5] =Integer.rotateLeft(x[5],7);

                    x[1] += x[6];  x[12]^= x[1];  x[12]=Integer.rotateLeft(x[12],16);
                    x[11]+= x[12]; x[6] ^= x[11]; x[6] =Integer.rotateLeft(x[6],12);
                    x[1] += x[6];  x[12]^= x[1];  x[12]=Integer.rotateLeft(x[12],8);
                    x[11]+= x[12]; x[6] ^= x[11]; x[6] =Integer.rotateLeft(x[6],7);

                    x[2] += x[7];  x[13]^= x[2];  x[13]=Integer.rotateLeft(x[13],16);
                    x[8] += x[13]; x[7] ^= x[8];  x[7] =Integer.rotateLeft(x[7],12);
                    x[2] += x[7];  x[13]^= x[2];  x[13]=Integer.rotateLeft(x[13],8);
                    x[8] += x[13]; x[7] ^= x[8];  x[7] =Integer.rotateLeft(x[7],7);

                    x[3] += x[4];  x[14]^= x[3];  x[14]=Integer.rotateLeft(x[14],16);
                    x[9] += x[14]; x[4] ^= x[9];  x[4] =Integer.rotateLeft(x[4],12);
                    x[3] += x[4];  x[14]^= x[3];  x[14]=Integer.rotateLeft(x[14],8);
                    x[9] += x[14]; x[4] ^= x[9];  x[4] =Integer.rotateLeft(x[4],7);
                }
                for (int i = 0; i < 16; i++) {
                    int v = x[i] + state[i];
                    blockS[i*4  ] = (byte)(v       );
                    blockS[i*4+1] = (byte)(v >>>  8);
                    blockS[i*4+2] = (byte)(v >>> 16);
                    blockS[i*4+3] = (byte)(v >>> 24);
                }
                int chunk = Math.min(remaining, 64);
                for (int i = 0; i < chunk; i++) {
                    out[offset + i] = (byte)(in[offset + i] ^ blockS[i]);
                }
                remaining -= chunk;
                offset += chunk;
                state[12]++;
            }

            return new String(out, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            t.printStackTrace();
            return null;
        }
    }
}
