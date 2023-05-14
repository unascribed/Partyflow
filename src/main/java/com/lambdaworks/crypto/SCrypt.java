/*
 * Copyright 2011 Will Glozer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Copyright (C) 2011 - Will Glozer.  All rights reserved.

package com.lambdaworks.crypto;

import java.io.UnsupportedEncodingException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import com.google.common.base.Charsets;
import com.google.common.io.BaseEncoding;

/**
 * Simple {@link SCryptKDF} interface for hashing passwords using the
 * <a href="http://www.tarsnap.com/scrypt.html">scrypt</a> key derivation function
 * and comparing a plain text password to a hashed one. The hashed output is an
 * extended implementation of the Modular Crypt Format that also includes the scrypt
 * algorithm parameters.
 *
 * Format: <code>$s0$PARAMS$SALT$KEY</code>.
 *
 * <dl>
 * <dd>PARAMS</dd><dt>32-bit hex integer containing log2(N) (16 bits), r (8 bits), and p (8 bits)</dt>
 * <dd>SALT</dd><dt>base64-encoded salt</dt>
 * <dd>KEY</dd><dt>base64-encoded derived key</dt>
 * </dl>
 *
 * <code>s0</code> identifies version 0 of the scrypt format, using a 128-bit salt and 256-bit derived key.
 *
 * @author  Will Glozer
 */
public class SCrypt {
	private static final SecureRandom RAND = new SecureRandom();
	private static final BaseEncoding BASE64 = BaseEncoding.base64();
	
	/**
	 * Hash the supplied plaintext password and generate output in the format described
	 * in {@link SCrypt}.
	 *
	 * @param passwd    Password.
	 * @param N         CPU cost parameter.
	 * @param r         Memory cost parameter.
	 * @param p         Parallelization parameter.
	 *
	 * @return The hashed password.
	 */
	public static String scrypt(String passwd, int N, int r, int p) {
		try {
			byte[] salt = new byte[16];
			synchronized (RAND) {
				RAND.nextBytes(salt);
			}

			byte[] derived = SCryptKDF.scrypt(passwd.getBytes(Charsets.UTF_8), salt, N, r, p, 32);

			String params = Long.toString(log2(N) << 16L | r << 8 | p, 16);

			StringBuilder sb = new StringBuilder((salt.length + derived.length) * 2);
			sb.append("$s0$").append(params).append('$');
			sb.append(encode(salt)).append('$');
			sb.append(encode(derived));

			return sb.toString();
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("JVM doesn't support SHA1PRNG or HMAC_SHA256?");
		}
	}

	private static String encode(byte[] bys) {
		return BASE64.encode(bys);
	}

	/**
	 * Compare the supplied plaintext password to a hashed password.
	 *
	 * @param   passwd  Plaintext password.
	 * @param   hashed  scrypt hashed password.
	 *
	 * @return true if passwd matches hashed value.
	 */
	public static boolean check(String passwd, String hashed) {
		try {
			String[] parts = hashed.split("\\$");

			if (parts.length != 5 || !parts[1].equals("s0")) {
				throw new IllegalArgumentException("Invalid hashed value");
			}

			long params = Long.parseLong(parts[2], 16);
			byte[] salt = decode(parts[3]);
			byte[] derived0 = decode(parts[4]);

			int N = (int) Math.pow(2, params >> 16 & 0xffff);
			int r = (int) params >> 8 & 0xff;
			int p = (int) params      & 0xff;

			byte[] derived1 = SCryptKDF.scrypt(passwd.getBytes("UTF-8"), salt, N, r, p, 32);

			if (derived0.length != derived1.length) return false;

			int result = 0;
			for (int i = 0; i < derived0.length; i++) {
				result |= derived0[i] ^ derived1[i];
			}
			return result == 0;
		} catch (UnsupportedEncodingException | GeneralSecurityException e) {
			throw new AssertionError(e);
		}
	}

	private static byte[] decode(String str) {
		return BASE64.decode(str);
	}

	private static int log2(int n) {
		int log = 0;
		if ((n & 0xffff0000 ) != 0) { n >>>= 16; log = 16; }
		if (n >= 256) { n >>>= 8; log += 8; }
		if (n >= 16 ) { n >>>= 4; log += 4; }
		if (n >= 4  ) { n >>>= 2; log += 2; }
		return log + (n >>> 1);
	}
}
