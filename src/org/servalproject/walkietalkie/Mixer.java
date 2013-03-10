package org.servalproject.walkietalkie;

import java.util.ArrayList;
import java.util.List;

import android.os.SystemClock;
import android.util.Log;

/**
 * Audio stream mixer.
 * 
 * Supports only 2 byte per sample (it is always the case once the audio sample is decoded).
 * 
 * @author Romain Vimont (®om) <rom@rom1v.com>
 * 
 */
public class Mixer {

	private static final String TAG = "Mixer";

	private static final int BUFFER_MAX_IDLE_TIME = 600; /* ms without writes */
	private static final int MAX_PLAYING_LAG = 50; /* ms */

	private int rate;
	private int bufferMs;
	private long origin; /* timestamp of cursor=0, in milliseconds */
	private int cursor; /* number of next mix sample to read */
	private int delayInSamples; /* delay of read */

	private boolean closed;

	public Mixer(int rate, int bufferMs, int delayInSamples) {
		this.rate = rate;
		this.bufferMs = bufferMs;
		this.delayInSamples = delayInSamples;
	}

	public static int toSamples(int delayInMs, int rate) {
		return delayInMs * rate / 1000;
	}

	public synchronized int getRate() {
		return rate;
	}

	public synchronized int getBufferMs() {
		return bufferMs;
	}

	public long getOrigin() {
		return origin;
	}

	public int getCursor() {
		return cursor;
	}

	private class Source {

		private int ssrc;
		private StreamBuffer streamBuffer;
		private long lastTouch;

		private Source(int ssrc, int sampleOffset) {
			this.ssrc = ssrc;
			this.streamBuffer = new StreamBuffer(2 * rate * bufferMs / 1000, 2 * sampleOffset);
		}

		private void touch() {
			lastTouch = SystemClock.elapsedRealtime();
		}
	}

	public List<Source> sources = new ArrayList<Source>();

	private int getSourceIndex(int ssrc) {
		int len = sources.size();
		int i = 0;
		while (i < len && sources.get(i).ssrc != ssrc) {
			i++;
		}
		return i == len ? -1 : i;
	}

	private long nextGCTimestamp;

	private void removeOldSources() {
		long now = SystemClock.elapsedRealtime();
		if (nextGCTimestamp > now)
			return; // later
		long notOlderThan = now - BUFFER_MAX_IDLE_TIME;
		int i = sources.size() - 1;
		long min = Long.MAX_VALUE;
		while (i >= 0) {
			if (sources.get(i).lastTouch < notOlderThan) {
				sources.remove(i);
			} else {
				min = Math.min(sources.get(i).lastTouch, min);
				nextGCTimestamp = min + BUFFER_MAX_IDLE_TIME;
			}
			i--;
		}
	}

	public synchronized void flush() {
		sources.clear();
	}

	private synchronized Source createSource(int ssrc, int sampleOffset) {
		int sourceIndex;
		boolean wasEmpty;
		if (sources.isEmpty()) {
			wasEmpty = true;
			sourceIndex = -1;
			cursor = 0;
			origin = SystemClock.elapsedRealtime();
		} else {
			wasEmpty = false;
			sourceIndex = getSourceIndex(ssrc);
		}
		Source source;
		if (sourceIndex == -1) {
			source = new Source(ssrc, sampleOffset);
			sources.add(source);
		} else {
			source = sources.get(sourceIndex);
		}
		if (wasEmpty) {
			notify();
		}
		return source;
	}

	public synchronized int write(int ssrc, int sampleOffset, byte[] data, int dataOffset,
			int dataLength) {
		if (WalkieTalkieService.DEBUG_WALKIE_TALKIE) {
			Log.d(TAG, "write(ssrc=" + ssrc + ", sampleOffset=" + sampleOffset + ", ...)");
		}
		Source source = createSource(ssrc, sampleOffset - delayInSamples);
		source.touch();
		return source.streamBuffer.write(2 * sampleOffset, data, dataOffset, dataLength);
	}

	public synchronized int read(byte[] data, int dataOffset, int dataLength) {
		removeOldSources();

		/* blocking read() */
		while (!closed && sources.size() == 0) {
			try {
				wait();
			} catch (InterruptedException e) {
				return 0;
			}
		}

		if (closed) {
			return 0;
		}

		/* wait for reading "at real time" */
		long now = SystemClock.elapsedRealtime();
		long target = origin + (cursor + dataLength / 2) * 1000 / rate;
		if (target > now) {
			try {
				Thread.sleep(target - now);
			} catch (InterruptedException e) {
				return 0;
			}
		} else if (target < now - MAX_PLAYING_LAG) {
			/* playing lag, eat bytes */
			int msToEat = (int) (now - target);
			int samplesToEat = toSamples(msToEat, rate);
			move(2 * samplesToEat);
			Log.w(TAG, "Playing lag: eat " + msToEat + " ms (" + samplesToEat + " samples)");
		}

		dataLength &= ~1; /* make dataLength even */
		int[] sum = new int[dataLength / 2];
		int n = sources.size();
		if (n == 0) {
			return 0;
		}

		int maxRead = 0;

		/* for each source, add value to the sum */
		for (Source src : sources) {
			byte[] buf = new byte[dataLength];
			int read = src.streamBuffer.read(buf, 0, dataLength);
			if (read > maxRead) {
				maxRead = read;
			}
			/* for each sample */
			for (int j = 0; j < read / 2; j++) {
				/* little endian */
				int sample = buf[2 * j] & 0xff | buf[2 * j + 1] << 8;
				sum[j] += sample;
			}
		}

		/* mix */
		for (int i = 0; i < maxRead / 2; i++) {
			/* z is the mix result between -1 and 1 */
			double z = sum[i] / (n * 32768.);
			/* use mix_f from http://blog.rom1v.com/2013/01/le-mixage-audio/ */
			int sgn = z >= 0 ? 1 : -1;
			double g = sgn * (1 - Math.pow(1 - sgn * z, n));
			int mix = toInt(g, 16);
			// int mix = sum[i] / n;

			/* little endian */
			data[2 * i + dataOffset] = (byte) mix;
			data[2 * i + 1 + dataOffset] = (byte) (mix >> 8);
		}

		return maxRead;
	}

	public synchronized void move(int bytes) {
		bytes &= ~1; /* make bytes even */
		for (int i = 0; i < sources.size(); i++) {
			sources.get(i).streamBuffer.move(bytes);
		}

		cursor += bytes / 2;
	}

	public synchronized void close() {
		closed = true;
		notifyAll();
	}

	/**
	 * Convert a sample having value between -1 and 1 to a signed int value on {@code bits} bits.
	 * 
	 * @param x
	 *            Sample value between -1 and 1.
	 * @param bits
	 *            Number of bits
	 * @return Signed int sample value.
	 */
	private static int toInt(double x, int bits) {
		int maxAmpl = 1 << (bits - 1); // 2^(bits-1)
		int res = (int) (x * maxAmpl);
		if (res == maxAmpl) {
			res = maxAmpl - 1;
		}
		return res;
	}

}
