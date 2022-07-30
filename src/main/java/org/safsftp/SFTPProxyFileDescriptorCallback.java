package org.safsftp;

import android.os.ProxyFileDescriptorCallback;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.util.Log;

import java.io.IOException;

import com.trilead.ssh2.Connection;
import com.trilead.ssh2.SFTPv3Client;
import com.trilead.ssh2.SFTPv3FileHandle;

public class SFTPProxyFileDescriptorCallback extends ProxyFileDescriptorCallback {
	private SFTPv3Client sftp;
	private SFTPv3FileHandle file;

	public SFTPProxyFileDescriptorCallback(SFTPv3Client sftp, SFTPv3FileHandle file) {
		this.sftp = sftp;
		this.file = file;
	}

	@Override
	public long onGetSize() throws ErrnoException {
		try {
			return sftp.fstat(file).size;
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
				int batch = (size - doff) > 32768 ? 32768 : (size - doff);
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
			sftp.closeFile(file);
		} catch (IOException e) {
		} // TODO
	}
}
