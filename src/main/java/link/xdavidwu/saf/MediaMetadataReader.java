package link.xdavidwu.saf;

import android.media.MediaMetadataRetriever;
import android.media.MediaMetadata;
import android.media.ExifInterface;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;

import java.io.FileDescriptor;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

public final class MediaMetadataReader {
	private static final Map<Integer, String> NAME_MAPPING_VIDEO = new HashMap<>();
	private static final Map<Integer, String> NAME_MAPPING_AUDIO = new HashMap<>();

	private static final int TYPE_INT = 0;

	private static final Map<Integer, Integer> TYPE_MAPPING = new HashMap<>();

	static {
		NAME_MAPPING_VIDEO.put(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH, ExifInterface.TAG_IMAGE_WIDTH);
		TYPE_MAPPING.put(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH, TYPE_INT);

		NAME_MAPPING_VIDEO.put(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT, ExifInterface.TAG_IMAGE_LENGTH);
		TYPE_MAPPING.put(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT, TYPE_INT);

		// in ms
		NAME_MAPPING_VIDEO.put(MediaMetadataRetriever.METADATA_KEY_DURATION, MediaMetadata.METADATA_KEY_DURATION);
		NAME_MAPPING_AUDIO.put(MediaMetadataRetriever.METADATA_KEY_DURATION, MediaMetadata.METADATA_KEY_DURATION);
		TYPE_MAPPING.put(MediaMetadataRetriever.METADATA_KEY_DURATION, TYPE_INT);

		NAME_MAPPING_AUDIO.put(MediaMetadataRetriever.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ARTIST);

		NAME_MAPPING_AUDIO.put(MediaMetadataRetriever.METADATA_KEY_COMPOSER, MediaMetadata.METADATA_KEY_COMPOSER);

		NAME_MAPPING_AUDIO.put(MediaMetadataRetriever.METADATA_KEY_ALBUM, MediaMetadata.METADATA_KEY_ALBUM);
	}

	public static final String METADATA_KEY_VIDEO = "android.media.metadata.video";
	public static final String METADATA_KEY_AUDIO = "android.media.metadata.audio";
	public static final String METADATA_VIDEO_LATITUDE = "android.media.metadata.video:latitude";
	public static final String METADATA_VIDEO_LONGITUTE = "android.media.metadata.video:longitude";

	// group 1, 3
	private static final Pattern iso6709d = Pattern.compile("^([+-]\\d\\d(\\.\\d+)?)([+-]\\d\\d\\d(\\.\\d+)?)");

	public static boolean isSupportedMimeType(String mimeType) {
		return isSupportedVideoMimeType(mimeType) ||
			isSupportedAudioMimeType(mimeType);
	}

	// Not sure what formats MediaMetadataRetriever supports, let's try all
	private static boolean isSupportedVideoMimeType(String mimeType) {
		return mimeType.startsWith("video/");
	}

	private static boolean isSupportedAudioMimeType(String mimeType) {
		return mimeType.startsWith("audio/");
	}

	private static float[] parseISO6709(String s) {
		var dMatcher = iso6709d.matcher(s);
		if (dMatcher.find()) {
			try {
				return new float[] {
					Float.parseFloat(dMatcher.group(1)),
					Float.parseFloat(dMatcher.group(3)),
				};
			} catch (NumberFormatException e) {
				return null;
			}
		}
		// TODO min and sec?
		return null;
	}

	// compatibility with pre 29
	private static class AutoCloseableMediaMetadataRetriever
			extends MediaMetadataRetriever implements AutoCloseable {
		@Override
		public void close() throws IOException {
			if (Build.VERSION.SDK_INT >= 29) {
				super.close();
			}
		}
	}

	public static void getMetadata(Bundle metadata, FileDescriptor fd,
			String mimeType) {
		String metadataType;
		Map<Integer, String> nameMapping;
		if (isSupportedVideoMimeType(mimeType)) {
			metadataType = METADATA_KEY_VIDEO;
			nameMapping = NAME_MAPPING_VIDEO;
		} else if (isSupportedAudioMimeType(mimeType)) {
			metadataType = METADATA_KEY_AUDIO;
			nameMapping = NAME_MAPPING_AUDIO;
		} else {
			return;
		}
		Bundle typeSpecificMetadata = new Bundle();

		try (var retriever = new UncheckedAutoCloseable<AutoCloseableMediaMetadataRetriever>(
					new AutoCloseableMediaMetadataRetriever())) {
			retriever.c().setDataSource(fd);

			for (int key: nameMapping.keySet()) {
				String raw = retriever.c().extractMetadata(key);
				if (raw == null) {
					continue;
				}

				Integer type = TYPE_MAPPING.get(key);
				if (type == null) {
					typeSpecificMetadata.putString(nameMapping.get(key), raw);
				} else {
					switch (type) {
					case TYPE_INT:
						typeSpecificMetadata.putInt(nameMapping.get(key), Integer.parseInt(raw));
						break;
					}
				}
			}

			String iso6709 = retriever.c().extractMetadata(
				MediaMetadataRetriever.METADATA_KEY_LOCATION);
			if (iso6709 != null) {
				var latlng = parseISO6709(iso6709);
				if (latlng != null) {
					// XXX DocumentsUI does not use address locator a la with exif
					typeSpecificMetadata.putFloat(METADATA_VIDEO_LATITUDE, latlng[0]);
					typeSpecificMetadata.putFloat(METADATA_VIDEO_LONGITUTE, latlng[1]);
				}
			}
		}
		metadata.putBundle(metadataType, typeSpecificMetadata);
		String[] types = {metadataType};
		metadata.putStringArray(DocumentsContract.METADATA_TYPES, types);
	}
}
