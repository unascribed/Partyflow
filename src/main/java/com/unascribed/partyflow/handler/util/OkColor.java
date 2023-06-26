/*
 * This file is part of Partyflow.
 *
 * Partyflow is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * Partyflow is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public
 * License along with Partyflow.
 *
 * If not, see <https://www.gnu.org/licenses/>.
 */

package com.unascribed.partyflow.handler.util;

public class OkColor {

	public float l, a, b;

	public OkColor(float l, float a, float b) {
		this.l = l;
		this.a = a;
		this.b = b;
	}
	
	@Override
	public String toString() {
		return String.format("oklab(%d%% %.3f %.3f)", (int)(l*100), a, b);
	}

	public int toRGB() {
		float l_ = l + 0.3963377774f * a + 0.2158037573f * b;
		float m_ = l - 0.1055613458f * a - 0.0638541728f * b;
		float s_ = l - 0.0894841775f * a - 1.2914855480f * b;

		float l = l_ * l_ * l_;
		float m = m_ * m_ * m_;
		float s = s_ * s_ * s_;

		return pack(
				delinearize(+4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s),
				delinearize(-1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s),
				delinearize(-0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s)
			);
	}

	private static int pack(float r, float g, float b) {
		r = Math.min(1, Math.max(0, r));
		g = Math.min(1, Math.max(0, g));
		b = Math.min(1, Math.max(0, b));
		return (Math.round(r*255)&0xFF)<<16|(Math.round(g*255)&0xFF)<<8|(Math.round(b*255)&0xFF);
	}


	public static OkColor fromRGB(int rgb) {
		float r = linearize(((rgb>>16)&0xFF)/255f);
		float g = linearize(((rgb>>8)&0xFF)/255f);
		float b = linearize(((rgb>>0)&0xFF)/255f);

		float l = 0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b;
		float m = 0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b;
		float s = 0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b;

		float l_ = (float) Math.cbrt(l);
		float m_ = (float) Math.cbrt(m);
		float s_ = (float) Math.cbrt(s);

		return new OkColor(
				0.2104542553f * l_ + 0.7936177850f * m_ - 0.0040720468f * s_,
				1.9779984951f * l_ - 2.4285922050f * m_ + 0.4505937099f * s_,
				0.0259040371f * l_ + 0.7827717662f * m_ - 0.8086757660f * s_
			);
	}

	private static float delinearize(float x) {
		if (x >= 0.0031308f)
			return (1.055f) * (float) Math.pow(x, 1.0 / 2.4) - 0.055f;
		else
			return 12.92f * x;
	}

	private static float linearize(float x) {
		if (x >= 0.04045f)
			return (float) Math.pow(((x + 0.055) / (1 + 0.055)), 2.4);
		else
			return x / 12.92f;
	}

}
