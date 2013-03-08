package org.servalproject.walkietalkie;

import org.servalproject.servald.AbstractId.InvalidBinaryException;
import org.servalproject.servald.SubscriberId;
import org.servalproject.servald.mdp.MeshSocketAddress;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Wrapper of {@link MeshSocketAddress} implementing {@link Parcelable}.
 * 
 * @author Romain Vimont (®om) <rom@rom1v.com>
 * 
 */
public class WalkieTalkieRecipient implements Parcelable {

	private MeshSocketAddress addr;

	public WalkieTalkieRecipient(MeshSocketAddress addr) {
		this.addr = addr;
	}

	public MeshSocketAddress getAddr() {
		return addr;
	}

	public static WalkieTalkieRecipient[] wrap(MeshSocketAddress... addr) {
		int len = addr.length;
		WalkieTalkieRecipient[] recipients = new WalkieTalkieRecipient[len];
		for (int i = 0; i < len; i++) {
			recipients[i] = new WalkieTalkieRecipient(addr[i]);
		}
		return recipients;
	}

	public static <T> MeshSocketAddress[] unwrap(T... recipients) {
		int len = recipients.length;
		MeshSocketAddress[] addr = new MeshSocketAddress[len];
		for (int i = 0; i < len; i++) {
			addr[i] = ((WalkieTalkieRecipient) recipients[i]).getAddr();
		}
		return addr;
	}

	@Override
	public int describeContents() {
		return 0;
	}

	@Override
	public void writeToParcel(Parcel dest, int flags) {
		dest.writeByteArray(addr.getSid().binary);
		dest.writeInt(addr.getPort());
	}

	public static final Parcelable.Creator<WalkieTalkieRecipient> CREATOR = new Parcelable.Creator<WalkieTalkieRecipient>() {

		@Override
		public WalkieTalkieRecipient createFromParcel(Parcel source) {
			try {
				byte[] rawSid = source.createByteArray();
				SubscriberId sid = new SubscriberId(rawSid);
				int port = source.readInt();
				MeshSocketAddress addr = new MeshSocketAddress(sid, port);
				return new WalkieTalkieRecipient(addr);
			} catch (InvalidBinaryException e) {
				throw new RuntimeException(e);
			}

		}

		@Override
		public WalkieTalkieRecipient[] newArray(int size) {
			return new WalkieTalkieRecipient[size];
		}

	};

}
