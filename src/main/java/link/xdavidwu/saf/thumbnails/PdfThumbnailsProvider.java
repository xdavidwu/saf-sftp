package link.xdavidwu.saf.thumbnails;

import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.pdf.PdfRenderer;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.ParcelFileDescriptor.AutoCloseOutputStream;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public class PdfThumbnailsProvider extends AbstractBitmapThumbnailsProvider {
	private static final String TAG = "PdfThumbnailsProvider";

	@Override
	public boolean isSupportedMimeType(String mimeType) {
		return "application/pdf".equals(mimeType);
	}

	@Override
	public Bitmap getThumbnailBitmap(ParcelFileDescriptor fd,
			Point sizeHint, CancellationSignal signal) throws IOException {
		try (var renderer = new PdfRenderer(fd.dup())) {
			try (var page = renderer.openPage(0)) {
				var pageWidth = page.getWidth();
				var pageHeight = page.getHeight();
				var widthFilledHeight = (int)
					(((double) sizeHint.x) / pageWidth * pageHeight);
				var heightFilledWidth = (int)
					(((double) sizeHint.y) / pageHeight * pageWidth);
				var bitmap = (widthFilledHeight > sizeHint.y) ?
					Bitmap.createBitmap(sizeHint.x, widthFilledHeight,
						Bitmap.Config.ARGB_8888) :
					Bitmap.createBitmap(heightFilledWidth, sizeHint.y,
						Bitmap.Config.ARGB_8888);

				bitmap.eraseColor(Color.WHITE);
				page.render(bitmap, null, null,
					PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);

				fd.close();
				return bitmap;
			}
		} catch (SecurityException se) {
			Log.i(TAG, "failed to open pdf due to password protection", se);
		}
		return null;
	}
}
