package link.xdavidwu.saf.metadata;

import android.app.AuthenticationRequiredException;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

public interface SuppliesMetadataViaProviders {
	static final String TAG = "SuppliesMetadataViaProviders";
	static final List<MetadataProvider> DEFAULT_PROVIDERS = List.of(
		new ExifMetadataProvider(), new AudioMetadataProvider(), new VideoMetadataProvider());

	// forwarded from DocumentsProvider
	ParcelFileDescriptor openDocument(String documentId, String mode,
		CancellationSignal signal)
			throws AuthenticationRequiredException, FileNotFoundException;

	// forwarded from DocumentsProvider
	String getDocumentType(String documentId)
		throws AuthenticationRequiredException, FileNotFoundException;

	default List<MetadataProvider> getMetadataProviders() {
		return DEFAULT_PROVIDERS;
	}

	default boolean typeSupportsMetadata(String mimeType) {
		return getMetadataProviders().stream()
			.anyMatch(p -> p.isSupportedMimeType(mimeType));
	}

	// NOTES: DocumentsProvider provides a concrete getDocumentMetadata()
	// (that just throws), so manual wiring is required
	default Bundle getDocumentMetadataViaProviders(String documentId)
			throws AuthenticationRequiredException, FileNotFoundException {
		var mimeType = getDocumentType(documentId);
		var provider = getMetadataProviders().stream()
			.filter(p -> p.isSupportedMimeType(mimeType)).findFirst();
		if (provider.isEmpty()) {
			return null;
		}

		var fd = openDocument(documentId, "r", null);
		var metadata = new Bundle();
		try {
			provider.get().getMetadata(metadata, fd, mimeType);
		} catch (IOException e) {
			Log.e(TAG, "cannot extract metadata", e);
		}
		return metadata;
	}
}
