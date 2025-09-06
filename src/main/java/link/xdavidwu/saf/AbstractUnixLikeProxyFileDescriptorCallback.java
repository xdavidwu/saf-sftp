package link.xdavidwu.saf;

import android.os.ProxyFileDescriptorCallback;
import android.system.ErrnoException;
import android.system.OsConstants;

import java.io.IOException;

public abstract class AbstractUnixLikeProxyFileDescriptorCallback
		extends ProxyFileDescriptorCallback {

	// translate platform-specific IOException to errno
	protected int translateIOException(IOException e) {
		return OsConstants.EIO;
	}

	protected interface LongIOOperation {
		long execute() throws IOException;
	}

	protected interface IntIOOperation {
		int execute() throws IOException;
	}

	protected long io(String name, LongIOOperation o) throws ErrnoException {
		try {
			return o.execute();
		} catch (IOException e) {
			throw new ErrnoException(name, translateIOException(e), e);
		}
	}

	protected int io(String name, IntIOOperation o) throws ErrnoException {
		try {
			return o.execute();
		} catch (IOException e) {
			throw new ErrnoException(name, translateIOException(e), e);
		}
	}
};
