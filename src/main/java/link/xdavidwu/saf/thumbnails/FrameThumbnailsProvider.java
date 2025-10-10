package link.xdavidwu.saf.thumbnails;

import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

import link.xdavidwu.saf.AutoCloseableMediaMetadataRetriever;

public class FrameThumbnailsProvider extends AbstractBitmapThumbnailsProvider {
	private static final String TAG = "FrameThumbnailsProvider";

	@Override
	public boolean isSupportedMimeType(String mimeType) {
		return mimeType.startsWith("video/");
	}

	@Override
	public Bitmap getThumbnailBitmap(ParcelFileDescriptor fd,
			Point sizeHint, CancellationSignal signal) throws IOException {
		try (var mmr = new AutoCloseableMediaMetadataRetriever()) {
			mmr.setDataSource(fd.getFileDescriptor());

			var frame = mmr.getFrameAtTime();
			if (frame != null) {
				fd.close();
				return frame;
			}
		}
		return null;
	}
}
