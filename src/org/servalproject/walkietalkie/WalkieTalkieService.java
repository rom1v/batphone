package org.servalproject.walkietalkie;

import org.servalproject.ServalBatPhoneApplication;
import org.servalproject.ServalBatPhoneApplication.State;
import org.servalproject.servald.mdp.MeshSocketAddress;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.os.Parcelable;

/**
 * Walkie-talkie Android service.
 * 
 * @author Romain Vimont (®om) <rom@rom1v.com>
 * 
 */
public class WalkieTalkieService extends Service {

	/**
	 * Start speaking action.
	 * 
	 * Parameters:
	 * <ul>
	 * <li>{@link EXTRA_RECIPIENTS}</li>
	 * </ul>
	 */
	public static final String ACTION_START_SPEAKING = "org.servalproject.walkietalkie.START_SPEAKING";

	/** Stop speaking action. */
	public static final String ACTION_STOP_SPEAKING = "org.servalproject.walkietalkie.STOP_SPEAKING";

	/** Start listening action. */
	public static final String ACTION_START_LISTENING = "org.servalproject.walkietalkie.START_LISTENING";

	/** Stop listening action. */
	public static final String ACTION_STOP_LISTENING = "org.servalproject.walkietalkie.STOP_LISTENING";

	/** Value is {@link WalkieTalkieRecipient[]}. */
	public static final String EXTRA_RECIPIENTS = "recipients";

	/** Mesh socket server port for walkie-talkie communication. */
	public static final int WALKIE_TALKIE_SERVER_PORT = 4444;

	/** Mesh socket client port for walkie-talkie communication. */
	public static final int WALKIE_TALKIE_CLIENT_PORT = 5555;

	/** Debug traces make the audio stream jerky. */
	public static final boolean DEBUG_WALKIE_TALKIE = false;

	private AudioSender sender;
	private AudioReceiver receiver;

	private boolean state;

	private boolean listeningAsked;
	private boolean speakingAsked;
	private MeshSocketAddress[] recipients = {};

	private BroadcastReceiver stateChangeReceiver = new BroadcastReceiver() {

		@Override
		public void onReceive(Context context, Intent intent) {
			final int stateOrd = intent.getIntExtra(ServalBatPhoneApplication.EXTRA_STATE, 0);
			updateState(State.values()[stateOrd]);
		}
	};

	@Override
	public void onCreate() {
		super.onCreate();

		sender = new AudioSender(WALKIE_TALKIE_CLIENT_PORT);
		receiver = new AudioReceiver(WALKIE_TALKIE_SERVER_PORT);

		/* read the current state */
		updateState(getApplicationContext().getState());
		/* listen servald start/stop */
		registerReceiver(stateChangeReceiver, new IntentFilter(
				ServalBatPhoneApplication.ACTION_STATE));
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		/* do not listen servald start/stop anymore */
		unregisterReceiver(stateChangeReceiver);
		/* stop everything */
		stopSpeaking();
		stopListening();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (intent != null) {
			String action = intent.getAction();
			if (ACTION_START_SPEAKING.equals(action)) {
				/* extract recipient list */
				Parcelable[] recipients = intent.getParcelableArrayExtra(EXTRA_RECIPIENTS);
				/* unwrap socket addresses */
				MeshSocketAddress[] addrList = WalkieTalkieRecipient.unwrap(recipients);
				/* start speaking to the addresses when possible */
				startSpeakingWhenPossible(addrList);
			} else if (ACTION_STOP_SPEAKING.equals(action)) {
				stopSpeaking();
			} else if (ACTION_START_LISTENING.equals(action)) {
				startListeningWhenPossible();
			} else if (ACTION_STOP_LISTENING.equals(action)) {
				stopListening();
			}
			return Service.START_STICKY;
		}
		return super.onStartCommand(intent, flags, startId);
	}

	private void startSpeakingWhenPossible(MeshSocketAddress... recipients) {
		if (state) {
			startSpeaking(recipients);
		} else {
			speakingAsked = true;
		}
		this.recipients = recipients;
	}

	private void startListeningWhenPossible() {
		if (state) {
			startListening();
		} else {
			listeningAsked = true;
		}
	}

	private void startSpeaking(MeshSocketAddress... recipients) {
		sender.start(recipients);
	}

	private void stopSpeaking() {
		sender.stop();
		speakingAsked = false;
	}

	private void startListening() {
		receiver.start();
	}

	private void stopListening() {
		receiver.stop();
		listeningAsked = false;
	}

	private void updateState(State state) {
		boolean newState = state == State.On;
		this.state = newState;
		if (newState) {
			/* execute pending actions */
			if (listeningAsked) {
				startListening();
			}
			if (speakingAsked) {
				startSpeaking(recipients);
			}
		} else {
			if (sender != null) {
				sender.stop();
			}
			if (receiver != null) {
				receiver.stop();
			}
		}
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@Override
	public ServalBatPhoneApplication getApplicationContext() {
		return (ServalBatPhoneApplication) super.getApplicationContext();
	}

	public static void startSpeaking(Context context, MeshSocketAddress... recipients) {
		Intent intent = new Intent(ACTION_START_SPEAKING);
		intent.putExtra(EXTRA_RECIPIENTS, WalkieTalkieRecipient.wrap(recipients));
		context.startService(intent);
	}

	public static void stopSpeaking(Context context) {
		Intent intent = new Intent(ACTION_STOP_SPEAKING);
		context.startService(intent);
	}

	public static void startListening(Context context) {
		Intent intent = new Intent(ACTION_START_LISTENING);
		context.startService(intent);
	}

	public static void stopListening(Context context) {
		Intent intent = new Intent(ACTION_STOP_LISTENING);
		context.startService(intent);
	}

}
