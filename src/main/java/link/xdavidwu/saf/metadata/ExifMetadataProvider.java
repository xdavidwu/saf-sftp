package link.xdavidwu.saf.metadata;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import java.io.IOException;

public class ExifMetadataProvider implements MetadataProvider {

	@Override
	public boolean isSupportedMimeType(String mimeType) {
		return MetadataReader.isSupportedMimeType(mimeType);
	}

	@Override
	public void getMetadata(Bundle metadata, ParcelFileDescriptor fd,
			String mimeType) throws IOException {
		try (fd) {
			MetadataReader.getMetadata(metadata, fd.getFileDescriptor(),
				mimeType, null);
		}
	}
}
