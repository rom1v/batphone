package org.servalproject.walkietalkie;

import java.util.Arrays;

import android.util.Log;

/**
 * Stream buffer.
 * 
 * Contains a window (implemented as circular buffer) of {@code length} bytes of a stream, starting
 * at {@code streamOffset}.
 * 
 * @author Romain Vimont (®om) <rom@rom1v.com>
 * 
 */
public class StreamBuffer {

	private byte[] buf; /* Stream buffer */
	private int length; /* data.length */
	private int streamOffset; /* data[head] = stream[streamOffset] */
	private int head;

	public StreamBuffer(int length) {
		buf = new byte[length];
		this.length = length;
	}

	public StreamBuffer(int length, int streamOffset) {
		this(length);
		this.streamOffset = streamOffset;
	}

	public synchronized int write(int streamOffset, byte[] data, int dataOffset, int dataLength) {
		int start = streamOffset - this.streamOffset;

		Log.i("StreamBuffer", "--> " + (streamOffset - this.streamOffset));
		if (start > length) {
			Log.i("StreamBuffer", "Buffer overflow : " + this.streamOffset + " - " + streamOffset);
			/* buffer overflow (write too far on the right) */
			return 0;
		}

		if (start < 0) {
			Log.i("StreamBuffer", "Buffer underrun : " + this.streamOffset + " - " + streamOffset);
			/* buffer underrun (write too far on the left) */
			dataOffset -= start;
			dataLength += start;
			start = 0;
		}

		if (dataLength <= 0) {
			/* no data to write (out of range) */
			return 0;
		}

		if (dataLength > length) {
			/* ignore first bytes */
			dataOffset += dataLength - length;
			dataLength = length;
		}

		if (start + dataLength > length) {
			/* ignore last bytes */
			dataLength = length - start;
		}

		/* written bytes to array (dataLength can change below) */
		int written = dataLength;

		/* byte count for the right part (or -offset for the left part) */
		int r = Math.min(dataLength, length - head - start);
		if (r > 0) {
			System.arraycopy(data, dataOffset, buf, head + start, r); /* right part */
			dataOffset += r;
			dataLength -= r;
		}
		if (dataLength > 0) {
			int bufOffset = Math.max(0, -r);
			System.arraycopy(data, dataOffset, buf, bufOffset, dataLength); /* left part */
		}

		return written;
	}

	public synchronized int read(byte[] data, int dataOffset, int dataLength) {
		int r = Math.min(dataLength, length - head);
		System.arraycopy(buf, head, data, dataOffset, r);
		if (dataLength - r > 0) {
			System.arraycopy(buf, 0, data, dataOffset + r, dataLength - r);
		}
		return dataLength;
	}

	public synchronized void move(int bytes) {
		streamOffset += bytes;
		if (Math.abs(bytes) >= length) {
			Arrays.fill(buf, (byte) 0);
			head = 0;
			return;
		}
		if (bytes > 0) {
			/* byte count for the right part */
			int r = Math.min(bytes, length - head);
			Arrays.fill(buf, head, head + r, (byte) 0); /* right part */
			if (bytes - r > 0) {
				Arrays.fill(buf, 0, bytes - r, (byte) 0); /* left part */
			}
			head = (head + bytes) % length;
		} else if (bytes < 0) {
			int r = Math.min(-bytes, head);
			Arrays.fill(buf, head - r, head, (byte) 0); /* left part */
			if (bytes + r < 0) {
				Arrays.fill(buf, length + bytes + r, length, (byte) 0); /* right part */
			}
			head = (length + head + bytes) % length;
		}
	}

	public synchronized void flush() {
		Arrays.fill(buf, (byte) 0);
		streamOffset = 0;
		head = 0;
	}

}
