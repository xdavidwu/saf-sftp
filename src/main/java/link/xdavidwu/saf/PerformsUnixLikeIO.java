package link.xdavidwu.saf;

import android.os.ProxyFileDescriptorCallback;
import android.system.ErrnoException;

import java.io.IOException;

public interface PerformsUnixLikeIO {

	// translate platform-specific IOException to errno
	int translateIOException(IOException e);

	interface IOOperation<T> {
		T execute() throws IOException;
	}

	default <T> T io(String name, IOOperation<T> o) throws ErrnoException {
		try {
			return o.execute();
		} catch (IOException e) {
			throw new ErrnoException(name, translateIOException(e), e);
		}
	}
};
