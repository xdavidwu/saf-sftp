package link.xdavidwu.saf.thumbnails;

import android.app.AuthenticationRequiredException;
import android.content.res.AssetFileDescriptor;
import android.graphics.Point;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.OperationCanceledException;
import android.util.Log;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

public interface SuppliesThumbnailsViaProviders {
	static final String TAG = "SuppliesThumbnailsViaProviders";
	static final List<ThumbnailsProvider> DEFAULT_PROVIDERS = List.of(
		new ExifThumbnailsProvider(),
		new EmbeddedPictureThumbnailsProvider(),
		new ImageSelfThumbnailsProvider());

	// forwarded from DocumentsProvider
	ParcelFileDescriptor openDocument(String documentId, String mode,
		CancellationSignal signal)
			throws AuthenticationRequiredException, FileNotFoundException;

	// forwarded from DocumentsProvider
	String getDocumentType(String documentId)
		throws AuthenticationRequiredException, FileNotFoundException;

	default List<ThumbnailsProvider> getThumbnailsProviders() {
		return DEFAULT_PROVIDERS;
	}

	default boolean typeSupportsThumbnail(String mimeType) {
		return getThumbnailsProviders().stream()
			.anyMatch(p -> p.isSupportedMimeType(mimeType));
	}

	// NOTES: DocumentsProvider provides a concrete openDocumentThumbnail()
	// (that just throws), so manual wiring is required
	default AssetFileDescriptor openDocumentThumbnailViaProviders(
			String documentId, Point sizeHint, CancellationSignal signal)
			throws AuthenticationRequiredException, FileNotFoundException {
		var mimeType = getDocumentType(documentId);
		if (mimeType == null) {
			Log.e(TAG, "getDocumentType returns null");
			return null;
		}

		var fd = openDocument(documentId, "r", signal);
		try {
			var res = getThumbnailsProviders().stream()
				.filter(p -> p.isSupportedMimeType(mimeType))
				.map(p -> {
					signal.throwIfCanceled();
					try {
						return p.getThumbnail(fd, sizeHint, signal);
					} catch (IOException e) {
						Log.w(TAG, "cannot get thumbnail from provider " + p.getClass().getSimpleName(), e);
						return null;
					}
				}).filter(Objects::nonNull).findFirst();
			if (res.isEmpty()) {
				try {
					fd.close();
				} catch (IOException e) {
					Log.w(TAG, "cannot close file", e);
				}
				throw new FileNotFoundException();
			}
			return res.get();
		} catch (OperationCanceledException oce) {
			try {
				fd.close();
			} catch (IOException e) {
				Log.w(TAG, "cannot close file", e);
			}
			throw oce;
		}
	}
}
