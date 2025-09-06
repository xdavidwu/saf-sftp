package link.xdavidwu.saf.sftp;

import android.os.ProxyFileDescriptorCallback;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.util.Log;

import java.io.IOException;

import org.apache.sshd.sftp.client.SftpClient;

public class SftpProxyFileDescriptorCallback extends ProxyFileDescriptorCallback {
	private static final int OPENSSH_SFTP_MAX_MSG_LENGTH = 256 * 1024;
	private static final int OPENSSH_SFTP_MAX_READ_LENGTH = OPENSSH_SFTP_MAX_MSG_LENGTH - 1024;
	private static final int OPENSSH_SFTP_MAX_WRITE_LENGTH = OPENSSH_SFTP_MAX_MSG_LENGTH - 1024;

	private SftpClient sftp;
	private SftpClient.CloseableHandle file;

	public SftpProxyFileDescriptorCallback(
			SftpClient sftp, SftpClient.CloseableHandle file) {
		this.sftp = sftp;
		this.file = file;
	}

	@Override
	public long onGetSize() throws ErrnoException {
		try {
			return sftp.stat(file).getSize();
		} catch (IOException e) {
			throw new ErrnoException("fstat", OsConstants.EIO);
		}
	}

	@Override
	public int onRead(long offset, int size, byte[] data) throws ErrnoException {
		try {
			Log.v("SFTP", "r: " + size + "@" + offset);
			int doff = 0;
			while (doff < size) {
				int batch = (size - doff) > OPENSSH_SFTP_MAX_READ_LENGTH ?
					OPENSSH_SFTP_MAX_READ_LENGTH : (size - doff);
				int r = sftp.read(file, offset + doff, data, doff, batch);
				Log.v("SFTP", "pr: " + batch + "@" + (offset + doff) + "=" + r);
				doff += r;
				if (r == -1) {
					return doff;
				}
			}
			return size;
		} catch (IOException e) {
			throw new ErrnoException("read", OsConstants.EIO);
		}
	}

	@Override
	public void onRelease() {
		try {
			sftp.close(file);
		} catch (IOException e) {
		} // TODO
	}
}
