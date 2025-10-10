package link.xdavidwu.saf.thumbnails;

import android.content.res.AssetFileDescriptor;
import android.graphics.Point;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;

import java.io.IOException;

public interface ThumbnailsProvider {

	boolean isSupportedMimeType(String mimeType);

	// fd should be closed by implementation if it returns non-null
	// and does not adopt it for AssetFileDescriptor
	AssetFileDescriptor getThumbnail(ParcelFileDescriptor fd, Point sizeHint,
		CancellationSignal signal) throws IOException;
}
