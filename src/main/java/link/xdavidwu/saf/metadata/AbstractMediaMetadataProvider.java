package link.xdavidwu.saf.metadata;

import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;

import java.io.IOException;
import java.util.Map;

public abstract class AbstractMediaMetadataProvider implements MetadataProvider {

	protected enum Type {
		STRING,
		INT,
	}

	protected record KeyInfo(String metadataKey, Type type) {
		public KeyInfo(String metadataKey) {
			this(metadataKey, Type.STRING);
		}
	}

	protected abstract String getMetadataType();

	protected abstract Map<Integer, KeyInfo> getMetadataKeyMap();

	// compatibility with pre 29
	private static class AutoCloseableMediaMetadataRetriever
			extends MediaMetadataRetriever implements AutoCloseable {
		@Override
		public void close() throws IOException {
			super.release();
		}
	}

	protected void extractUnmappedMetadata(Bundle typeSpecificMetadata,
			MediaMetadataRetriever mmr) {
	}

	@Override
	public void getMetadata(Bundle metadata, ParcelFileDescriptor fd,
			String mimeType) throws IOException {
		if (!isSupportedMimeType(mimeType)) {
			return;
		}
		Bundle typeSpecificMetadata = new Bundle();

		try (var mmr = new AutoCloseableMediaMetadataRetriever()) {
			mmr.setDataSource(fd.getFileDescriptor());
			fd.close();

			getMetadataKeyMap().forEach((mmrKey, info) -> {
				var raw = mmr.extractMetadata(mmrKey);
				if (raw == null) {
					return;
				}

				switch (info.type()) {
				case INT:
					typeSpecificMetadata.putInt(info.metadataKey(), Integer.parseInt(raw));
					break;
				case STRING:
					typeSpecificMetadata.putString(info.metadataKey(), raw);
					break;
				}
			});

			extractUnmappedMetadata(typeSpecificMetadata, mmr);
		}

		var metadataType = getMetadataType();
		metadata.putBundle(metadataType, typeSpecificMetadata);
		String[] types = {metadataType};
		metadata.putStringArray(DocumentsContract.METADATA_TYPES, types);
	}
}
