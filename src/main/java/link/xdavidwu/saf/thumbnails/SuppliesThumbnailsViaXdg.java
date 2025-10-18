package link.xdavidwu.saf.thumbnails;

import android.app.AuthenticationRequiredException;
import android.content.res.AssetFileDescriptor;
import android.graphics.Point;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.util.LruCache;

import java.io.FileNotFoundException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import link.xdavidwu.saf.UriAsDocumentId;

public interface SuppliesThumbnailsViaXdg extends UriAsDocumentId {
	// TODO consider sized ones
	static final String XDG_THUMBNAIL_NORMAL_DIR = ".sh_thumbnails/normal/";
	static final String[] XDG_THUMBNAIL_DIRS = {
		XDG_THUMBNAIL_NORMAL_DIR
	};

	static LruCache<String, String> xdgThumbnailNameCache =
			new LruCache<String, String>(1 * 1024 * 1024) {
		private static MessageDigest md5;
		static {
			try {
				md5 = MessageDigest.getInstance("MD5");
			} catch (NoSuchAlgorithmException e) {
				throw new RuntimeException(e);
			}
		}
		private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

		// String.hashCode should be faster than md5
		@Override
		protected String create(String key) {
			// 16 bytes
			var digest = md5.digest(("./" + key).getBytes());
			var hex = new char[32];
			for (int i = 0; i < 16; i++) {
				hex[i * 2] = HEX_DIGITS[(digest[i] & 0xf0) >>> 4];
				hex[i * 2 + 1] = HEX_DIGITS[digest[i] & 0xf];
			}
			return String.valueOf(hex);
		}

		@Override
		protected int sizeOf(String key, String value) {
			return 32;
		}
	};

	default String getXDGThumbnailFile(String name) {
		return xdgThumbnailNameCache.get(name) + ".png";
	}

	// forwarded from DocumentsProvider
	ParcelFileDescriptor openDocument(String documentId, String mode,
		CancellationSignal signal)
			throws AuthenticationRequiredException, FileNotFoundException;

	default AssetFileDescriptor openDocumentThumbnailViaXdg(
			String documentId, Point sizeHint, CancellationSignal signal)
			throws FileNotFoundException {
		var path = pathFromDocumentId(documentId);
		var filename = basename(path);
		var thumbnailDocumentId = toParentDocumentId(documentId) + '/' +
			XDG_THUMBNAIL_NORMAL_DIR + getXDGThumbnailFile(filename);
		try {
			var fd = openDocument(thumbnailDocumentId, "r", signal);
			return new AssetFileDescriptor(fd, 0, fd.getStatSize());
		} catch (Exception e) {
		}

		throw new FileNotFoundException();
	}
}
