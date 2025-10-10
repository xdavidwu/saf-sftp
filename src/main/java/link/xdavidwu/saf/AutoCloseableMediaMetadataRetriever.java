package link.xdavidwu.saf;

import android.media.MediaMetadataRetriever;

import java.io.IOException;

// compatibility with pre 29
public class AutoCloseableMediaMetadataRetriever
		extends MediaMetadataRetriever implements AutoCloseable {
	@Override
	public void close() throws IOException {
		super.release();
	}
}

