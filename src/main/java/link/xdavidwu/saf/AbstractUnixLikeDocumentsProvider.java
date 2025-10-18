package link.xdavidwu.saf;

import android.provider.DocumentsContract.Document;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;
import android.util.Log;

import java.io.FileNotFoundException;
import java.util.Arrays;

import link.xdavidwu.saf.thumbnails.SuppliesThumbnailsViaProviders;

/*
 * Helpers and partial implementation of a DocumentsProvider with a POSIX
 * filesystem from Unix-like environment that utilize XDG specs, including:
 *
 * - documentId <--> path translation
 * - isChildDocument
 * - Implementation of openDocumentThumbnail via
 *   - XDG thumbnail spec
 *   - EXIF thumbnail
 *		- openDocument should support streaming, without downloading the whole file
 * - MIME types, via mode_t and filename
 *
 * documentId is assumed to be a URI with authority, which path is the path,
 * but root is an empty path instead of /.
 */
public abstract class AbstractUnixLikeDocumentsProvider
		extends DocumentsProvider implements SuppliesThumbnailsViaProviders {
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

	@Override
	public boolean isChildDocument(String parentDocumentId, String documentId) {
		return documentId.startsWith(parentDocumentId) &&
			documentId.charAt(parentDocumentId.length()) == '/';
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
}
