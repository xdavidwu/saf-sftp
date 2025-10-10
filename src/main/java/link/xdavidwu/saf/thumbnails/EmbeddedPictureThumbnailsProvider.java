package link.xdavidwu.saf.thumbnails;

import android.content.res.AssetFileDescriptor;
import android.graphics.Point;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.ParcelFileDescriptor.AutoCloseOutputStream;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

import link.xdavidwu.saf.AutoCloseableMediaMetadataRetriever;

public class EmbeddedPictureThumbnailsProvider implements ThumbnailsProvider {
	private static final String TAG = "EmbeddedPictureThumbnailsProvider";

	@Override
	public boolean isSupportedMimeType(String mimeType) {
		return mimeType.startsWith("video/") || mimeType.startsWith("audio/");
	}

	@Override
	public AssetFileDescriptor getThumbnail(ParcelFileDescriptor fd,
			Point sizeHint, CancellationSignal signal) throws IOException {
		try (var mmr = new AutoCloseableMediaMetadataRetriever()) {
			mmr.setDataSource(fd.getFileDescriptor());

			var pic = mmr.getEmbeddedPicture();
			if (pic != null) {
				fd.close();

				var pipe = ParcelFileDescriptor.createReliablePipe();
				CompletableFuture.runAsync(() -> {
					try {
						try (var o = new AutoCloseOutputStream(pipe[1])) {
							o.write(pic);
						}
					} catch (IOException e) {
						Log.w(TAG, "exception on pipe", e);
					}
				});
				return new AssetFileDescriptor(pipe[0], 0, pic.length);
			}
		}
		return null;
	}
}
