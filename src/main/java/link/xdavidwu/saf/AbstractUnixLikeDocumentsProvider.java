package link.xdavidwu.saf;

import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Point;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.ParcelFileDescriptor.AutoCloseInputStream;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;
import android.util.Log;
import android.util.LruCache;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.function.Function;

/*
 * Helpers and partial implementation of a DocumentsProvider with a POSIX
 * filesystem from Unix-like environment that utilize XDG specs, including:
 *
 * - Documents URI <--> path translation
 * - isChildDocument
 * - Implementation of openDocumentThumbnail via
 *   - XDG thumbnail spec
 *   - EXIF thumbnail on SDK >= 30
 *		- openDocument should support streaming, without downloading the whole file
 * - MIME types, via mode_t and filename
 *
 */
public abstract class AbstractUnixLikeDocumentsProvider extends DocumentsProvider {
	private static final String LOG_NAME = "AbstractUnixLikeDocumentsProvider";

	// TODO consider sized ones
	protected static final String XDG_THUMBNAIL_NORMAL_DIR = ".sh_thumbnails/normal/";

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

	// an URI without path part
	// TODO support multiple roots
	protected abstract Uri getRootUri();

	protected String pathFromDocumentId(String documentId) {
		return Uri.parse(documentId).getPath();
	}

	protected String documentIdFromPath(String path) {
		return getRootUri().buildUpon().path(path).build().toString();
	}

	protected String toParentDocumentId(String documentId) {
		var uri = Uri.parse(documentId);
		var segments = uri.getPathSegments();
		var builder = uri.buildUpon().path("/");
		segments.subList(0, segments.size() - 1).forEach(
			seg -> builder.appendPath(seg));
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
		int dirIndex = path.lastIndexOf("/");
		String filename = path.substring(dirIndex + 1);
		String dir = path.substring(0, dirIndex + 1);
		String thumbnailPath = dir + XDG_THUMBNAIL_NORMAL_DIR + getXDGThumbnailFile(filename);
		try {
			ParcelFileDescriptor fd = openDocument(documentIdFromPath(thumbnailPath), "r", signal);
			return new AssetFileDescriptor(fd, 0, fd.getStatSize());
		} catch (Exception e) {
		}

		if (Build.VERSION.SDK_INT >= 30 && ExifInterface.isSupportedMimeType(getDocumentType(documentId))) {
			ParcelFileDescriptor fd = openDocument(documentId, "r", null);

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
		try (var c = new UncheckedAutoCloseable<Cursor>(queryDocument(
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

	// translate platform-specific IOException to java-native subtypes
	protected IOException translateIOException(IOException e) {
		return e;
	}

	protected interface IOOperation<T> {
		T execute() throws IOException;
	}

	protected <T> T io(IOOperation<T> o, Function<IOException, T> handle)
			throws FileNotFoundException {
		try {
			return o.execute();
		} catch (IOException|UncheckedIOException oe) {
			var e = translateIOException(
				oe instanceof UncheckedIOException u ? u.getCause() :
				(IOException) oe);
			if (e instanceof FileNotFoundException f) {
				throw f;
			}
			return handle.apply(e);
		}
	}

	protected <T> Optional<T> ioWithCursor(Cursor c, IOOperation<T> o)
			throws FileNotFoundException {
		return io(() -> Optional.of(o.execute()), e -> {
			var extras = new Bundle();
			var msg = Optional.ofNullable(e.getMessage())
				.flatMap(s -> s.length() != 0 ? Optional.of(s) : Optional.empty())
				.orElse(e.getClass().getName());
			extras.putString(DocumentsContract.EXTRA_ERROR, msg);
			c.setExtras(extras);
			return Optional.empty();
		});
	}

	protected <T> T ioToUnchecked(IOOperation<T> o)
			throws FileNotFoundException {
		return io(o, e -> {
			throw new UncheckedIOException(e);
		});
	}

	// Helpers for ioWithCursor, to short circuit out with
	// ioWithCursor().orElseThrow(this::haltIt)
	protected class HaltWithCursorException extends Exception {
		public static final long serialVersionUID = 42;
	}
	protected HaltWithCursorException haltIt() {
		return new HaltWithCursorException();
	}

	protected interface QueryOperation {
		public void execute()
			throws FileNotFoundException, HaltWithCursorException;
	}

	protected Cursor performQuery(Cursor c, QueryOperation o)
			throws FileNotFoundException {
		try {
			o.execute();
		} catch (HaltWithCursorException e) {
		}
		return c;
	}
}
