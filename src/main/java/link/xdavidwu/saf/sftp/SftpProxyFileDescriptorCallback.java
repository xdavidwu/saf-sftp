package link.xdavidwu.saf.sftp;

import android.os.ProxyFileDescriptorCallback;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.util.Log;

import java.io.IOException;

import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;

import link.xdavidwu.saf.AbstractUnixLikeProxyFileDescriptorCallback;

public class SftpProxyFileDescriptorCallback
		extends AbstractUnixLikeProxyFileDescriptorCallback {
	private static final int OPENSSH_SFTP_MAX_MSG_LENGTH = 256 * 1024;
	private static final int OPENSSH_SFTP_MAX_WRITE_LENGTH = OPENSSH_SFTP_MAX_MSG_LENGTH - 1024;

	private SftpClient sftp;
	private SftpClient.CloseableHandle file;

	public SftpProxyFileDescriptorCallback(
			SftpClient sftp, SftpClient.CloseableHandle file) {
		this.sftp = sftp;
		this.file = file;
	}

	@Override
	protected int translateIOException(IOException e) {
		if (e instanceof SftpException s) {
			return switch (s.getStatus()) {
			case SftpConstants.SSH_FX_NO_SUCH_FILE		-> OsConstants.ENOENT;
			case SftpConstants.SSH_FX_PERMISSION_DENIED	-> OsConstants.EACCES;
			case SftpConstants.SSH_FX_BAD_MESSAGE		-> OsConstants.EINVAL;
			case SftpConstants.SSH_FX_NO_CONNECTION		-> OsConstants.ENOTCONN;
			case SftpConstants.SSH_FX_CONNECTION_LOST	-> OsConstants.ENOTCONN;
			case SftpConstants.SSH_FX_OP_UNSUPPORTED	-> OsConstants.EOPNOTSUPP;
			case SftpConstants.SSH_FX_INVALID_HANDLE	-> OsConstants.EBADF;
			case SftpConstants.SSH_FX_NO_SUCH_PATH		-> OsConstants.ENOENT;
			case SftpConstants.SSH_FX_FILE_ALREADY_EXISTS -> OsConstants.EEXIST;
			case SftpConstants.SSH_FX_WRITE_PROTECT		-> OsConstants.EPERM;
			case SftpConstants.SSH_FX_NO_SPACE_ON_FILESYSTEM -> OsConstants.ENOSPC;
			case SftpConstants.SSH_FX_QUOTA_EXCEEDED	-> OsConstants.EDQUOT;
			case SftpConstants.SSH_FX_UNKNOWN_PRINCIPAL	-> OsConstants.EINVAL;
			case SftpConstants.SSH_FX_DIR_NOT_EMPTY		-> OsConstants.ENOTEMPTY;
			case SftpConstants.SSH_FX_NOT_A_DIRECTORY	-> OsConstants.ENOTDIR;
			case SftpConstants.SSH_FX_INVALID_FILENAME	-> OsConstants.EINVAL;
			case SftpConstants.SSH_FX_LINK_LOOP			-> OsConstants.ELOOP;
			case SftpConstants.SSH_FX_INVALID_PARAMETER	-> OsConstants.EINVAL;
			case SftpConstants.SSH_FX_FILE_IS_A_DIRECTORY -> OsConstants.EISDIR;
			case SftpConstants.SSH_FX_OWNER_INVALID		-> OsConstants.EINVAL;
			case SftpConstants.SSH_FX_GROUP_INVALID		-> OsConstants.EINVAL;
			default -> OsConstants.EIO;
			};
		}
		return super.translateIOException(e);
	}

	@Override
	public long onGetSize() throws ErrnoException {
		return io("stat", () -> sftp.stat(file).getSize());
	}

	@Override
	public int onRead(long offset, int size, byte[] data) throws ErrnoException {
		return io("read", () -> {
			Log.v("SFTP", "r: " + size + "@" + offset);
			int read = 0;
			while (read < size) {
				int r = sftp.read(file, offset + read, data, read, size - read);
				Log.v("SFTP", "pr: " + (size - read) + "@" + (offset + read) + "=" + r);
				if (r == -1) {
					return read;
				}
				read += r;
			}
			return size;
		});
	}

	@Override
	public void onRelease() {
		try {
			sftp.close(file);
		} catch (IOException e) {
		}
	}
}
