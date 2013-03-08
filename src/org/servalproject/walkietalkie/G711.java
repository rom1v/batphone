package org.servalproject.walkietalkie;

/**
 * Encode and decode linear to A-law.
 * 
 * Implementation deduced from principle explained on Wikipedia:
 * http://fr.wikipedia.org/wiki/Loi_A#Transformation_discr.C3.A8te
 * 
 * @author Romain Vimont (®om)
 * 
 */
public class G711 {

	/**
	 * Encode a linear sample to A-Law.
	 * 
	 * @param linear
	 *            Linear sample.
	 * @return A-Law sample.
	 */
	public static byte encodeALaw(int linear) {
		/* @formatter:off
		 * 
		 * (if s=1, then the remaining of "linear" is 2's complement)
		 * in                out
		 * s0000000wxyz----  s000wxyz
		 * s0000001wxyz----  s001wxyz
		 * s000001wxyz-----  s010wxyz
		 * s00001wxyz------  s011wxyz
		 * s0001wxyz-------  s100wxyz
		 * s001wxyz--------  s101wxyz
		 * s01wxyz---------  s110wxyz
		 * s1wxyz----------  s111wxyz
		 */
		int sign = linear < 0 ? 0x80 : 0;
		if (linear < 0) {
			linear = -linear;
		}
		int sample11 = (linear >> 4) & 0x7ff; /* 11 most significant bits unsigned */
		int prefix = 7;
		int tmp = sample11;
		while (prefix > 0 && (tmp & 0x400) == 0) {
			prefix--;
			tmp <<= 1;
		}
		int wxyz;
		if (prefix == 0) {
			wxyz = sample11 & 0xf;
		} else {
			wxyz = (tmp >> 6) & 0xf;
		}
		byte res = (byte) (sign | (prefix << 4) | wxyz);
		return res;
	}

	/**
	 * Decode an A-Law sample to linear.
	 * 
	 * @param alaw
	 *            A-Law sample.
	 * @return Linear sample.
	 */
	public static int decodeALaw(byte alaw) {
		/* @formatter:off
		 * 
		 * (if s=1, then the remaining of "linear" is 2's complement)
		 * in        out
		 * s000wxyz  s0000000wxyz0000
		 * s001wxyz  s0000001wxyz0000
		 * s010wxyz  s000001wxyz00000
		 * s011wxyz  s00001wxyz000000
		 * s100wxyz  s0001wxyz0000000
		 * s101wxyz  s001wxyz00000000
		 * s110wxyz  s01wxyz000000000
		 * s111wxyz  s1wxyz0000000000
		 */
		int prefix = (alaw >> 4) & 7;
		int wxyz = alaw & 0xf;
		int res;
		if (prefix == 0) {
			res = wxyz << 4;
		} else {
			res = (0x10 | wxyz) << (prefix + 3);
		}
		if (alaw < 0) {
			res = -res;
		}
		return res;
	}

}
