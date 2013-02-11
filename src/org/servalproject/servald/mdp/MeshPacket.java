package org.servalproject.servald.mdp;

import java.net.DatagramPacket;

import org.servalproject.servald.SubscriberId;

/**
 * Equivalent of {@link DatagramPacket} for MDP protocol.
 * 
 * @author Romain Vimont (®om) <rom@rom1v.com>
 */
public class MeshPacket {

	/** Data buffer. */
	private byte[] buf;

	/** Offset. */
	private int offset;

	/** Length. */
	private int length;

	/** SID address. */
	private SubscriberId sid;

	/** Port. */
	private int port = -1;

	/**
	 * Constructs a mesh packet for receiving packets of length {@code length} with offset
	 * {@code offset}.
	 * 
	 * The {@code length} argument must be less than or equal to {@code buf.length}.
	 * 
	 * @param buf
	 *            buffer for holding the incoming data.
	 * @param offset
	 *            offset.
	 * @param length
	 *            the number of bytes to read.
	 */
	public MeshPacket(byte[] buf, int offset, int length) {
		setData(buf, offset, length);
	}

	/**
	 * Constructs a mesh packet for receiving packets of length {@code length}.
	 * 
	 * The {@code length} argument must be less than or equal to {@code buf.length}.
	 * 
	 * @param buf
	 *            buffer for holding the incoming data.
	 * @param length
	 *            the number of bytes to read.
	 */
	public MeshPacket(byte[] buf, int length) {
		this(buf, 0, length);
	}

	/**
	 * Constructs a mesh packet for sending packets of length {@code length} with offset
	 * {@code offset} to the specified port number on the specified host. The {@code length}
	 * argument must be less than or equal to {@code buf.length}.
	 * 
	 * @param buf
	 *            packet data.
	 * @param offset
	 *            offset.
	 * @param length
	 *            length.
	 * @param destination
	 *            SID address.
	 * @param destination
	 *            port.
	 */
	public MeshPacket(byte[] buf, int offset, int length, SubscriberId sid, int port) {
		this(buf, offset, length);
		setSid(sid);
		setPort(port);
	}

	/**
	 * Constructs a mesh packet for sending packets of length {@code length} to the specified port
	 * number on the specified host. The {@code length} argument must be less than or equal to
	 * {@code buf.length}.
	 * 
	 * @param buf
	 *            packet data.
	 * @param length
	 *            length.
	 * @param destination
	 *            SID address.
	 * @param destination
	 *            port.
	 */
	public MeshPacket(byte[] buf, int length, SubscriberId sid, int port) {
		this(buf, 0, length, sid, port);
	}

	/**
	 * Constructs a mesh packet for sending packets of length {@code length} with offset
	 * {@code offset} to the specified port number on the specified host. The {@code length}
	 * argument must be less than or equal to {@code buf.length}.
	 * 
	 * @param buf
	 *            packet data.
	 * @param offset
	 *            offset.
	 * @param length
	 *            length.
	 * @param address
	 *            destination address.
	 */
	public MeshPacket(byte[] buf, int offset, int length, MeshSocketAddress address) {
		setData(buf, offset, length);
		setMeshSocketAddress(address);
	}

	/**
	 * Constructs a mesh packet for sending packets of length {@code length} to the specified port
	 * number on the specified host. The {@code length} argument must be less than or equal to
	 * {@code buf.length}.
	 * 
	 * @param buf
	 *            packet data.
	 * @param length
	 *            length.
	 * @param address
	 *            destination address.
	 */
	public MeshPacket(byte[] buf, int length, MeshSocketAddress address) {
		this(buf, 0, length, address);
	}

	/**
	 * Set the data buffer, with the specified offset and length.
	 * 
	 * @param buf
	 *            buffer.
	 * @param offset
	 *            offset.
	 * @param length
	 *            length.
	 */
	public synchronized void setData(byte[] buf, int offset, int length) {
		/* this will check to see if buf is null */
		if (length < 0 || offset < 0 || length + offset < 0 || length + offset > buf.length) {
			throw new IllegalArgumentException("Illegal length or offset");
		}
		this.buf = buf;
		this.offset = offset;
		this.length = length;
	}

	/**
	 * Set the data buffer, with the offset set to 0, and the length set to the length of
	 * {@code buf}.
	 * 
	 * @param buf
	 *            buffer.
	 */
	public synchronized void setData(byte[] buf) {
		setData(buf, 0, buf.length);
	}

	/**
	 * Sets the SID address of the machine to which this mesh packet is being sent.
	 * 
	 * @param sid
	 *            SID address.
	 */
	public synchronized void setSid(SubscriberId sid) {
		this.sid = sid;
	}

	/**
	 * Sets the port number on the remote host to which this mesh packet is being sent.
	 * 
	 * @param port
	 *            port.
	 */
	public synchronized void setPort(int port) {
		this.port = port;
	}

	/**
	 * Sets the mesh socket address of the remote host to which this packet is being sent.
	 * 
	 * @param address
	 *            mesh socket address.
	 */
	public synchronized void setMeshSocketAddress(MeshSocketAddress address) {
		setSid(address.getSid());
		setPort(address.getPort());
	}

	/**
	 * Set the length for this packet. The length of the packet is the number of bytes from the
	 * packet's data buffer that will be sent, or the number of bytes of the packet's data buffer
	 * that will be used for receiving data. The length must be lesser or equal to the offset plus
	 * the length of the packet's buffer.
	 * 
	 * @param length
	 *            data length.
	 */
	public synchronized void setLength(int length) {
		if (length < 0 || length + offset < 0 || length + offset > buf.length) {
			throw new IllegalArgumentException("illegal length");
		}
		this.length = length;
	}

	/**
	 * Returns the data buffer. The data received or the data to be sent starts from the offset in
	 * the buffer, and runs for length long.
	 * 
	 * @return data buffer.
	 */
	public synchronized byte[] getBuf() {
		return buf;
	}

	/**
	 * Returns the offset of the data to be sent or the offset of the data received.
	 * 
	 * @return data offset.
	 */
	public synchronized int getOffset() {
		return offset;
	}

	/**
	 * Returns the length of the data to be sent or the length of the data received.
	 * 
	 * @return data length.
	 */
	public synchronized int getLength() {
		return length;
	}

	/**
	 * Returns the SID address of the machine to which this packet is being sent or from which the
	 * packet was received.
	 * 
	 * @return SID address.
	 */
	public synchronized SubscriberId getSid() {
		return sid;
	}

	/**
	 * Returns the port number on the remote host to which this packet is being sent or from which
	 * the packet was received.
	 * 
	 * @return Port.
	 */
	public synchronized int getPort() {
		return port;
	}

}
