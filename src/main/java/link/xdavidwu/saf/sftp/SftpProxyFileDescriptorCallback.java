package link.xdavidwu.saf.sftp;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.ProxyFileDescriptorCallback;
import android.os.IBinder;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.util.Log;

import java.io.IOException;

import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.extensions.openssh.OpenSSHFsyncExtension;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;

import link.xdavidwu.saf.PerformsUnixLikeIO;

public class SftpProxyFileDescriptorCallback
		extends ProxyFileDescriptorCallback implements PerformsUnixLikeIO {
	private static final int OPENSSH_SFTP_MAX_MSG_LENGTH = 256 * 1024;
	private static final int OPENSSH_SFTP_MAX_WRITE_LENGTH = OPENSSH_SFTP_MAX_MSG_LENGTH - 1024;

	private SftpClient sftp;
	private SftpClient.CloseableHandle file;
	private Context ctx;
	private ServiceConnection serviceConnection = new ServiceConnection() {
		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {}
		@Override
		public void onServiceDisconnected(ComponentName name) {}
	};

	public SftpProxyFileDescriptorCallback(
			SftpClient sftp, SftpClient.CloseableHandle file, Context ctx) {
		this.sftp = sftp;
		this.file = file;
		this.ctx = ctx;
		var intent = new Intent(ctx, SftpIOService.class);
		ctx.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
	}

	@Override
	public int translateIOException(IOException e) {
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
		return OsConstants.EIO;
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
	public int onWrite(long offset, int size, byte[] data)
			throws ErrnoException {
		return io("write", () -> {
			sftp.write(file, offset, data, 0, size);
			return size;
		});
	}

	@Override
	public void onFsync() throws ErrnoException {
		var fsync = sftp.getExtension(OpenSSHFsyncExtension.class);
		if (!fsync.isSupported()) {
			throw new ErrnoException("fsync", OsConstants.EOPNOTSUPP);
		}
		io("fsync", () -> {
			fsync.fsync(file);
			return null;
		});
	}

	@Override
	public void onRelease() {
		Log.v("SFTP", "release");
		try {
			sftp.close(file);
			sftp.close();
		} catch (IOException e) {
		}
		ctx.unbindService(serviceConnection);
	}
}
