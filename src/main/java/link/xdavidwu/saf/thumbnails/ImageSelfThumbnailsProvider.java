package link.xdavidwu.saf.thumbnails;

import android.content.res.AssetFileDescriptor;
import android.graphics.Point;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;

// TODO replace this with a thumbnailing, caching implementation
public class ImageSelfThumbnailsProvider implements ThumbnailsProvider {
	private final long sizeLimit;

	public ImageSelfThumbnailsProvider(long sizeLimit) {
		this.sizeLimit = sizeLimit;
	}

	public ImageSelfThumbnailsProvider() {
		this(Long.MAX_VALUE);
	}

	@Override
	public boolean isSupportedMimeType(String mimeType) {
		return mimeType.startsWith("image/");
	}

	@Override
	public AssetFileDescriptor getThumbnail(ParcelFileDescriptor fd,
			Point sizeHint, CancellationSignal signal) {
		var sz = fd.getStatSize();
		if (sz <= sizeLimit) {
			return new AssetFileDescriptor(fd, 0, sz);
		}
		return null;
	}
}
