package link.xdavidwu.saf;

import android.net.Uri;

public interface UriAsDocumentId {
	default String pathFromDocumentId(String documentId) {
		return Uri.parse(documentId).getPath();
	}

	default String documentIdFromPath(Uri root, String path) {
		return root.buildUpon().path(path).build().toString();
	}

	default String toParentDocumentId(String documentId) {
		var uri = Uri.parse(documentId);
		var segments = uri.getPathSegments();
		var builder = uri.buildUpon().path("");
		segments.subList(0, segments.size() > 0 ? segments.size() - 1 : 0)
			.forEach(seg -> builder.appendPath(seg));
		return builder.build().toString();
	}

	default String basename(String path) {
		return path.substring(path.lastIndexOf("/") + 1);
	}
}
