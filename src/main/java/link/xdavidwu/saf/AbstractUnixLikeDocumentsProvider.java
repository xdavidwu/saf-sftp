package link.xdavidwu.saf;

import android.content.res.AssetFileDescriptor;
import android.graphics.Point;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.ParcelFileDescriptor.AutoCloseInputStream;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;
import android.util.Log;
import android.util.LruCache;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/*
 * Helpers and partial implementation of a DocumentsProvider with a POSIX
 * filesystem from Unix-like environment that utilize XDG specs, including:
 *
 * - documentId <--> path translation
 * - isChildDocument
 * - Implementation of openDocumentThumbnail via
 *   - XDG thumbnail spec
 *   - EXIF thumbnail on SDK >= 30
 *		- openDocument should support streaming, without downloading the whole file
 * - MIME types, via mode_t and filename
 *
 * documentId is assumed to be a URI with authority, which path is the path,
 * but root is an empty path instead of /.
 */
public abstract class AbstractUnixLikeDocumentsProvider extends DocumentsProvider {
	private static final String LOG_NAME = "AbstractUnixLikeDocumentsProvider";

	// credentials and capabilities applicable for remote filesystem
	public record FsCreds(
			int uid, int gid, int[] supplementaryGroups,
			long effectiveCapabilities) {
		public static final int CAP_DAC_OVERRIDE = 1;
		public static final int CAP_DAC_READ_SEARCH = 2;

		public boolean hasGroup(int gid) {
			return this.gid == gid ||
				Arrays.asList(supplementaryGroups).contains(gid);
		}

		public boolean hasCapability(int cap) {
			var mask = 1l << cap;
			return (effectiveCapabilities & mask) == mask;
		}
	}

	// TODO consider sized ones
	protected static final String XDG_THUMBNAIL_NORMAL_DIR = ".sh_thumbnails/normal/";
	protected static final String[] XDG_THUMBNAIL_DIRS = {
		XDG_THUMBNAIL_NORMAL_DIR
	};

	protected static MimeTypeMap mimeTypeMap = MimeTypeMap.getSingleton();
	protected static String getTypeFromName(String filename) {
		var s = filename;
		while (true) {
			var mime = mimeTypeMap.getMimeTypeFromExtension(s);
			if (mime != null) {
				return mime;
			}
			var i = s.indexOf('.');
			if (i == -1) {
				break;
			}
			s = s.substring(i + 1);
		}
		return "application/octet-stream";
	}

	protected static final int S_IFMT = 0170000, S_IFSOCK = 0140000,
		S_IFLNK = 0120000, S_IFREG = 0100000, S_IFBLK = 0060000,
		S_IFDIR = 0040000, S_IFCHR = 0020000, S_IFIFO = 0010000;

	protected static String getType(int mode, String name) {
		return switch (mode & S_IFMT) {
		case S_IFSOCK -> "inode/socket";
		case S_IFLNK -> "inode/symlink";
		case S_IFREG -> getTypeFromName(name);
		case S_IFBLK -> "inode/blockdevice";
		case S_IFDIR -> Document.MIME_TYPE_DIR;
		case S_IFCHR -> "inode/chardevice";
		case S_IFIFO -> "inode/fifo";
		default -> "application/octet-stream";
		};
	}

	protected static int S_IR = 4, S_IW = 2, S_IX = 1;

	// extract applicable permission bits from mode, may be checked with
	// S_IR, S_IW, S_IX, capabilities are also considered
	protected int getModeBits(int mode, int uid, int gid, FsCreds fsCreds) {
		if (fsCreds == null) {
			return 7;
		}
		if (fsCreds.hasCapability(FsCreds.CAP_DAC_OVERRIDE)) {
			return 7;
		}
		var bits = (
			fsCreds.uid() == uid ? mode >> 6 :
			fsCreds.hasGroup(gid) ? mode >> 3 :
			mode
		) & 7;
		if (fsCreds.hasCapability(FsCreds.CAP_DAC_READ_SEARCH)) {
			bits |= S_IR | S_IX;
		}
		return bits;
	}

	protected String pathFromDocumentId(String documentId) {
		return Uri.parse(documentId).getPath();
	}

	protected String documentIdFromPath(Uri root, String path) {
		return root.buildUpon().path(path).build().toString();
	}

	protected String toParentDocumentId(String documentId) {
		var uri = Uri.parse(documentId);
		var segments = uri.getPathSegments();
		var builder = uri.buildUpon().path("");
		segments.subList(0, segments.size() > 0 ? segments.size() - 1 : 0)
			.forEach(seg -> builder.appendPath(seg));
		return builder.build().toString();
	}

	protected String basename(String path) {
		return path.substring(path.lastIndexOf("/") + 1);
	}

	@Override
	public boolean isChildDocument(String parentDocumentId, String documentId) {
		return documentId.startsWith(parentDocumentId) &&
			documentId.charAt(parentDocumentId.length()) == '/';
	}

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
	private static LruCache<String, String> xdgThumbnailNameCache =
			new LruCache<String, String>(1 * 1024 * 1024) {
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

		protected int sizeOf(String key, String value) {
			return 32;
		}
	};
	protected String getXDGThumbnailFile(String name) {
		return xdgThumbnailNameCache.get(name) + ".png";
	}

	protected boolean typeSupportsThumbnail(String mimeType) {
		return Build.VERSION.SDK_INT >= 30 &&
			ExifInterface.isSupportedMimeType(mimeType);
	}

	@Override
	public AssetFileDescriptor openDocumentThumbnail(String documentId,
			Point sizeHint, CancellationSignal signal)
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

		if (Build.VERSION.SDK_INT >= 30 && ExifInterface.isSupportedMimeType(getDocumentType(documentId))) {
			var fd = openDocument(documentId, "r", signal);

			var stream = new AutoCloseInputStream(fd);

			try {
				var exif = new ExifInterface(stream);
				var range = exif.getThumbnailRange();
				if (range != null) {
					return new AssetFileDescriptor(fd, range[0], range[1]);
				}
				stream.close();
			} catch (IOException e) {
			}
		}

		throw new FileNotFoundException();
	}

	// default implementation query without projection, which may be costly
	// TODO try to upstream the idea?
	@Override
	public String getDocumentType(String documentId)
			throws FileNotFoundException {
		try (var c = new UncheckedAutoCloseable<>(queryDocument(
				documentId, new String[]{Document.COLUMN_MIME_TYPE}))) {
			if (c.c().moveToFirst()) {
				return c.c().getString(
					c.c().getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE));
			} else {
				return null;
			}
		}
	}

	protected boolean typeSupportsMetadata(String mimeType) {
		return MediaMetadataReader.isSupportedMimeType(mimeType) ||
			MetadataReader.isSupportedMimeType(mimeType);
	}

	// getDocumentMetadata but with getDocumentType,
	// for downstream to extend without re-query
	public Bundle getDocumentMetadata(String documentId, String mimeType)
			throws FileNotFoundException {
		if (MetadataReader.isSupportedMimeType(mimeType)) {
			ParcelFileDescriptor fd = openDocument(documentId, "r", null);
			Bundle metadata = new Bundle();

			try (var stream = new UncheckedAutoCloseable<AutoCloseInputStream>(
						new AutoCloseInputStream(fd))) {
				MetadataReader.getMetadata(metadata, stream.c(), mimeType, null);
			} catch (IOException e) {
				Log.e(LOG_NAME, "getMetadata: ", e);
				return null;
			}
			return metadata;
		} else if (MediaMetadataReader.isSupportedMimeType(mimeType)) {
			Bundle metadata = new Bundle();
			try (var fd = new UncheckedAutoCloseable<ParcelFileDescriptor>(
						openDocument(documentId, "r", null))){
				MediaMetadataReader.getMetadata(metadata,
					fd.c().getFileDescriptor(), mimeType);
			}
			return metadata;
		}
		return null;
	}

	@Override
	public Bundle getDocumentMetadata(String documentId)
			throws FileNotFoundException {
		return getDocumentMetadata(documentId, getDocumentType(documentId));
	}
}
