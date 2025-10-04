package link.xdavidwu.saf.metadata;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.ParcelFileDescriptor.AutoCloseInputStream;

import java.io.IOException;

public class ExifMetadataProvider implements MetadataProvider {

	@Override
	public boolean isSupportedMimeType(String mimeType) {
		return MetadataReader.isSupportedMimeType(mimeType);
	}

	@Override
	public void getMetadata(Bundle metadata, ParcelFileDescriptor fd,
			String mimeType) throws IOException {
		try (var stream = new AutoCloseInputStream(fd)) {
			MetadataReader.getMetadata(metadata, stream, mimeType, null);
		}
	}
}
