package link.xdavidwu.saf.thumbnails;

import android.content.res.AssetFileDescriptor;
import android.graphics.Point;
import android.media.ExifInterface;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;

import java.io.IOException;

public class ExifThumbnailsProvider implements ThumbnailsProvider {

	@Override
	public boolean isSupportedMimeType(String mimeType) {
		if (Build.VERSION.SDK_INT >= 30) {
			return ExifInterface.isSupportedMimeType(mimeType);
		} else {
			return mimeType.startsWith("image/");
		}
	}

	@Override
	public AssetFileDescriptor getThumbnail(ParcelFileDescriptor fd,
			Point sizeHint, CancellationSignal signal) throws IOException {
		var exif = new ExifInterface(fd.getFileDescriptor());
		var range = exif.getThumbnailRange();
		if (range == null) {
			return null;
		}
		return new AssetFileDescriptor(fd, range[0], range[1]);
	}
}
