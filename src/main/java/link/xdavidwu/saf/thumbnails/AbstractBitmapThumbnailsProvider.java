package link.xdavidwu.saf.thumbnails;

import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.Point;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.ParcelFileDescriptor.AutoCloseOutputStream;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

import link.xdavidwu.saf.AutoCloseableMediaMetadataRetriever;

public abstract class AbstractBitmapThumbnailsProvider
		implements ThumbnailsProvider {
	private static final String TAG = "AbstractBitmapThumbnailsProvider";

	protected abstract Bitmap getThumbnailBitmap(ParcelFileDescriptor fd,
		Point sizeHint, CancellationSignal signal) throws IOException;

	@Override
	public AssetFileDescriptor getThumbnail(ParcelFileDescriptor fd,
			Point sizeHint, CancellationSignal signal) throws IOException {
		var bitmap = getThumbnailBitmap(fd, sizeHint, signal);
		if (bitmap == null) {
			return null;
		}

		var pipe = ParcelFileDescriptor.createReliablePipe();
		CompletableFuture.runAsync(() -> {
			try {
				try (var o = new AutoCloseOutputStream(pipe[1])) {
					if (Build.VERSION.SDK_INT >= 30) {
						bitmap.compress(CompressFormat.WEBP_LOSSLESS, 0, o);
					} else {
						bitmap.compress(CompressFormat.PNG, 0, o);
					}
				}
			} catch (IOException e) {
				Log.w(TAG, "exception on pipe", e);
			}
		});
		return new AssetFileDescriptor(pipe[0], 0,
			AssetFileDescriptor.UNKNOWN_LENGTH);
	}
}
