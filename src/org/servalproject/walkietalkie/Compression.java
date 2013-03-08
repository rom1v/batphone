package org.servalproject.walkietalkie;

/**
 * Compression type.
 * 
 * @author Romain Vimont (®om) <rom@rom1v.com>
 * 
 */
public enum Compression {

	NONE(1), TO_8_BITS(2), A_LAW(2);

	int ratio;

	Compression(int ratio) {
		this.ratio = ratio;
	}
}
