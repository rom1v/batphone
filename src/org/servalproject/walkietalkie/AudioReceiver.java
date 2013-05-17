package org.servalproject.walkietalkie;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.servalproject.servald.mdp.MeshPacket;
import org.servalproject.servald.mdp.MeshSocket;
import org.servalproject.servald.mdp.MeshSocketException;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.SystemClock;
import android.util.Log;

/**
 * Receive audio packets, bufferize and mix them, then play.
 *
 * @author Romain Vimont (®om) <rom@rom1v.com>
 *
 */
public class AudioReceiver {

	private final ExecutorService bufferizerExecutor = Executors.newSingleThreadExecutor();
	private final ExecutorService playerExecutor = Executors.newSingleThreadExecutor();

	private static final String TAG = "AudioReceiver";

	public static final Compression COMPRESSION = AudioSender.COMPRESSION;

	public static final int PACKET_SIZE = AudioSender.PACKET_SIZE;
	public static final int HEADER_SIZE = AudioSender.HEADER_SIZE;
	public static final int PAYLOAD_SIZE = AudioSender.PAYLOAD_SIZE;

	private static final int RATE = AudioSender.RATE; // Hz
	private static final int CHANNEL = AudioFormat.CHANNEL_OUT_MONO;
	private static final int FORMAT = AudioFormat.ENCODING_PCM_16BIT;
	private static final int PLAYER_BUFFER_SIZE = AudioTrack
			.getMinBufferSize(RATE, CHANNEL, FORMAT) * 2;

	private static final int BUFFER_MS = 1000;
	private static final int DELAY_IN_MS = 100;
	private static final int DELAY_IN_SAMPLES = Mixer.toSamples(DELAY_IN_MS, RATE);

	private MeshSocket socket;
	private int port;

	private Bufferizer bufferizer;
	private Player player;
	private Future<?> bufferizerFuture;
	private Future<?> playerFuture;

	public AudioReceiver(int port) {
		this.port = port;
	}

	public synchronized void start() {
		stop();
		Mixer mixer = new Mixer(RATE, BUFFER_MS, DELAY_IN_SAMPLES);

		bufferizer = new Bufferizer(mixer);
		bufferizerFuture = bufferizerExecutor.submit(bufferizer);

		player = new Player(mixer);
		playerFuture = playerExecutor.submit(player);
	}

	public synchronized void stop() {
		if (isRunning()) {
			bufferizerFuture.cancel(true);
			bufferizer.stopSocket();
			bufferizer = null;
			playerFuture.cancel(true);
			player.stopMixer();
			player = null;
		}
	}

	private synchronized boolean isRunning() {
		return bufferizer != null;
	}

	private class Bufferizer implements Runnable {

		private Mixer mixer;

		private volatile boolean stopped;

		Bufferizer(Mixer mixer) {
			this.mixer = mixer;
		}

		public synchronized void stopSocket() {
			stopped = true;
			if (socket != null) {
				/* close socket for unblocking receive */
				socket.close();
			}
		}

		@Override
		public void run() {
			try {
				int attempts = 0;
				do {
					try {
						synchronized (this) {
							if (stopped) {
								return;
							}
							/* try to initialize mesh socket */
							socket = new MeshSocket(port);
						}
					} catch (MeshSocketException e) {
						/*
						 * attempt failed, maybe because servald is not started yet (State becomes
						 * "on" *before* servald is really on, need to fix it upstream on batphone)
						 */

						if (++attempts >= 5) {
							/* definitively fail after 5 tries */
							throw e;
						}

						Log.e(TAG, "Receiver socket creation failed, retrying...", e);

						/* retry after 1.5 s */
						try {
							Thread.sleep(1500);
						} catch (InterruptedException ie) {
							/* do nothing, but sleep() is interrupted */
						}
					}
				} while (socket == null);

				byte[] buf = new byte[PACKET_SIZE];
				byte[] writeBuf = new byte[COMPRESSION.ratio * PAYLOAD_SIZE];
				MeshPacket packet = new MeshPacket(buf, PACKET_SIZE);

				int consecutiveFails = 0;
				while (!stopped) {
					try {
						socket.receive(packet);
						consecutiveFails = 0;

						int seq = (buf[0] & 0xff) << 8 | buf[1] & 0xff;
						int timestamp = (buf[2] & 0xff) << 24 | (buf[3] & 0xff) << 16
								| (buf[4] & 0xff) << 8 | buf[5] & 0xff;
						int ssrc = (buf[6] & 0xff) << 24 | (buf[7] & 0xff) << 16
								| (buf[8] & 0xff) << 8 | buf[9] & 0xff;

						int writeBufLength = decompress(buf, writeBuf, HEADER_SIZE,
								packet.getLength() - HEADER_SIZE);
						int written = mixer.write(ssrc, timestamp, writeBuf, 0, writeBufLength);

						if (WalkieTalkieService.DEBUG_WALKIE_TALKIE) {
							Log.d(TAG, "ssrc=" + ssrc + ", " + buf[6] + ":" + buf[7] + ":" + buf[8]
									+ ":" + buf[9]);
							Log.d(TAG, "(" + ssrc + ") Packet " + seq + "["
									+ packet.getBuf().length + "] " + written);
						}

					} catch (IOException e) {
						if (!stopped) {
							Log.e(TAG, "Cannot receive data", e);
							/* something is definitely wrong */
							if (++consecutiveFails > 3) {
								stopped = true;
							}
						}
					}
				}
			} catch (MeshSocketException e) {
				Log.e(TAG, "Cannot create receiver socket", e);
			}
		}
	};

	private class Player implements Runnable {

		private Mixer mixer;

		private AudioTrack audioTrack;

		private volatile boolean stopped;

		Player(Mixer mixer) {
			this.mixer = mixer;
		}

		public synchronized void stopMixer() {
			stopped = true;
			mixer.close();
		}

		@Override
		public void run() {
			try {
				synchronized (this) {
					if (stopped) {
						return;
					}
					audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, RATE, CHANNEL, FORMAT,
							PLAYER_BUFFER_SIZE, AudioTrack.MODE_STREAM);
				}

				byte[] buf = new byte[PAYLOAD_SIZE];

				while (!stopped) {
					int read;
					synchronized (mixer) {
						read = mixer.read(buf, 0, buf.length);

						if (stopped || read == 0) {
							return;
						}

						if (WalkieTalkieService.DEBUG_WALKIE_TALKIE) {
							Log.d(TAG, "mixerPlayer.read() : " + SystemClock.elapsedRealtime()
									+ " [" + read + "]");
						}
						mixer.move(read);
					}
					audioTrack.write(buf, 0, read);
					/* play and stop after playing this packet (unless another call play() again) */
					audioTrack.play();
					audioTrack.stop();
				}
			} finally {
				if (audioTrack != null) {
					audioTrack.release();
				}
			}
		}
	}

	private static int decompress(byte[] buf, byte[] writeBuf, int bufOffset, int bufPayloadLength) {
		switch (COMPRESSION) {
		case NONE:
			System.arraycopy(buf, bufOffset, writeBuf, 0, bufPayloadLength);
			return bufPayloadLength;
		case TO_8_BITS:
			/* convert 8 bits to 16 bits */
			for (int i = 0; i < bufPayloadLength; i++) {
				/* recreate lower bits read 8 bits (little endian) */
				writeBuf[2 * i + 1] = buf[bufOffset + i];
			}
			return bufPayloadLength * 2;
		case A_LAW:
			for (int i = 0; i < bufPayloadLength; i++) {
				byte alaw = buf[i + bufOffset];
				int sample = G711.decodeALaw(alaw);
				byte msb = (byte) (sample >> 8);
				byte lsb = (byte) sample;
				writeBuf[2 * i + 1] = msb;
				writeBuf[2 * i] = lsb;
			}
			return bufPayloadLength * 2;
		default:
			throw new UnsupportedOperationException(COMPRESSION + " not implemented");
		}
	}

}
