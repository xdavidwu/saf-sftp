package link.xdavidwu.saf.metadata;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import java.io.IOException;

public interface MetadataProvider {

	boolean isSupportedMimeType(String mimeType);

	// fd should be closed by implementation
	void getMetadata(Bundle metadata, ParcelFileDescriptor fd, String mimeType)
		throws IOException;
}
