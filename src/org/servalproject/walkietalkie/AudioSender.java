package org.servalproject.walkietalkie;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.servalproject.servald.mdp.MeshPacket;
import org.servalproject.servald.mdp.MeshSocket;
import org.servalproject.servald.mdp.MeshSocketAddress;
import org.servalproject.servald.mdp.MeshSocketException;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;
import android.util.Log;

/**
 * Record from micropohne, packetize and send audio packets.
 * 
 * @author Romain Vimont (®om) <rom@rom1v.com>
 * 
 */
public class AudioSender {

	private final ExecutorService workerExecutor = Executors.newSingleThreadExecutor();

	private static final String TAG = "AudioSender";

	private static final Random RANDOM = new Random();

	public static final Compression COMPRESSION = Compression.A_LAW;

	public static final int PACKET_SIZE = 512 / COMPRESSION.ratio;
	public static final int HEADER_SIZE = 10;
	public static final int PAYLOAD_SIZE = PACKET_SIZE - HEADER_SIZE;

	public static final int RATE = 8000; // Hz
	private static final int CHANNEL = AudioFormat.CHANNEL_IN_MONO;
	private static final int FORMAT = AudioFormat.ENCODING_PCM_16BIT;
	private static final int RECORDER_BUFFER_SIZE = AudioRecord.getMinBufferSize(RATE, CHANNEL,
			FORMAT) * 2;

	private int localPort;

	private Worker worker;
	private Future<?> future;

	public AudioSender(int localPort) {
		this.localPort = localPort;
	}

	public synchronized void start(MeshSocketAddress... recipients) {
		worker = new Worker(recipients);
		future = workerExecutor.submit(worker);
	}

	public synchronized void stop() {
		if (isRunning()) {
			future.cancel(true);
			worker.stopRecording();
			worker = null;
			future = null;
		}
	}

	private synchronized boolean isRunning() {
		return worker != null;
	}

	private class Worker implements Runnable {

		private MeshSocketAddress[] recipients;

		private AudioRecord audioRecord;

		private volatile boolean stopped;

		Worker(MeshSocketAddress... recipients) {
			this.recipients = recipients;
		}

		public synchronized void stopRecording() {
			stopped = true;
			if (audioRecord != null) {
				try {
					/* stop now for unblocking read */
					audioRecord.stop();
				} catch (IllegalStateException e) {
					/* do nothing */
				}
			}
		}

		@Override
		public void run() {
			MeshSocket socket = null;
			try {
				/* initialize mesh socket */
				socket = new MeshSocket(localPort);

				synchronized (this) {
					if (stopped) {
						return;
					}
					/* init audioRecord only if not already stopped */
					audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
							AudioFormat.CHANNEL_IN_MONO, FORMAT, RECORDER_BUFFER_SIZE);

					/* start recording microphone */
					audioRecord.startRecording();
				}

				/* packet headers */
				short seq = 0;
				int timestamp = 0;
				int ssrc = RANDOM.nextInt();

				/* packet buffer */
				byte[] buf = new byte[PACKET_SIZE];

				/* always read 16 bits / sample, but send 8 or 16 bits / sample. */
				byte[] readBuf = new byte[COMPRESSION.ratio * PAYLOAD_SIZE];

				/* static headers */
				buf[6] = (byte) (ssrc >> 24);
				buf[7] = (byte) (ssrc >> 16);
				buf[8] = (byte) (ssrc >> 8);
				buf[9] = (byte) ssrc;

				/* mesh packet */
				MeshPacket packet = new MeshPacket(buf, PACKET_SIZE);
				packet.setQos(MeshPacket.OQ_ISOCHRONOUS_VOICE);
				packet.setFlags(MeshPacket.FLAG_MDP_NOCRYPT | MeshPacket.FLAG_MDP_NOSIGN);

				long start = SystemClock.elapsedRealtime();
				long fromStart = 0; /* in milliseconds */
				int fromStartTU = 0; /* in timestamp units (for example 1 TU = 1 sample = 2 bytes) */

				while (!stopped) {
					Log.i(TAG, "Reading packet " + seq);

					/* dynamic headers */
					buf[0] = (byte) (seq >> 8);
					buf[1] = (byte) seq;
					buf[2] = (byte) (timestamp >> 24);
					buf[3] = (byte) (timestamp >> 16);
					buf[4] = (byte) (timestamp >> 8);
					buf[5] = (byte) timestamp;

					/* read from microphone */
					int read = audioRecord.read(readBuf, 0, COMPRESSION.ratio * PAYLOAD_SIZE);

					/* compress data */
					int bufLength = compress(readBuf, buf, HEADER_SIZE, read);

					packet.setLength(HEADER_SIZE + bufLength);

					/* send the packet to all recipients */
					for (MeshSocketAddress to : recipients) {
						packet.setSid(to.getSid());
						packet.setPort(to.getPort());
						try {
							Log.i(TAG, "Prepare packet " + seq + " [" + read / 2 + "] to send to "
									+ to.getSid() + ":" + to.getPort());
							socket.send(packet);
							Log.i(TAG,
									"Paquet " + seq + " sent to " + to.getSid() + ":"
											+ to.getPort());
						} catch (IOException e) {
							Log.e(TAG, "Cannot send data", e);
						}
					}

					long now = SystemClock.elapsedRealtime();
					fromStart = now - start;
					fromStartTU = (int) Math.max(0, fromStart * RATE / 1000);

					if (Math.abs(fromStartTU - timestamp) > 1000) {
						/*
						 * micro does not record exactly at the right rate, we have to correct it
						 * (here every 1k samples of deviation)
						 */
						Log.i(TAG, "--MICROPHONE DEVIATION CORRECTION-- timestamp was " + timestamp
								+ ", timestamp = " + fromStartTU);
						timestamp = fromStartTU;
					}
					Log.i(TAG, "theoretical-timestamp(" + fromStartTU + ") - recorded-timestamp("
							+ timestamp + ") = " + (fromStartTU - timestamp));

					seq++;
					timestamp += read / 2; /* 2 bytes per sample */
				}
			} catch (MeshSocketException e) {
				Log.e(TAG, "Cannot create mesh socket", e);
			} finally {
				if (audioRecord != null) {
					audioRecord.release();
				}
				if (socket != null) {
					socket.close();
				}
			}
		}
	};

	private static int compress(byte[] readBuf, byte[] buf, int bufOffset, int readBufLength) {
		switch (COMPRESSION) {
		case NONE:
			System.arraycopy(readBuf, 0, buf, bufOffset, readBufLength);
			return readBufLength;
		case TO_8_BITS:
			/* convert 16 bits to 8 bits */
			for (int i = 0; i < readBufLength / 2; i++) {
				byte b = readBuf[2 * i + 1];
				if (b < 0) {
					b++;
				}
				/* discard lower bits read 8 bits (little endian) */
				buf[bufOffset + i] = b;
			}
			return readBufLength / 2;
		case A_LAW:
			for (int i = 0; i < readBufLength / 2; i++) {
				int sample = readBuf[2 * i] & 0xff | readBuf[2 * i + 1] << 8; /* 16 bits signed */
				byte alaw = G711.encodeALaw(sample);
				buf[bufOffset + i] = alaw;
			}
			return readBufLength / 2;
		default:
			throw new UnsupportedOperationException(COMPRESSION + " not implemented");
		}
	}

}
