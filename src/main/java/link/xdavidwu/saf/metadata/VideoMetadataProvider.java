package link.xdavidwu.saf.metadata;

import android.media.ExifInterface;
import android.media.MediaMetadata;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

public class VideoMetadataProvider extends AbstractMediaMetadataProvider {
	private static final Map<Integer, KeyInfo> KEY_MAPPING = new HashMap<>();
	static {
		KEY_MAPPING.put(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
				new KeyInfo(ExifInterface.TAG_IMAGE_WIDTH, Type.INT));
		KEY_MAPPING.put(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
				new KeyInfo(ExifInterface.TAG_IMAGE_LENGTH, Type.INT));
		KEY_MAPPING.put(MediaMetadataRetriever.METADATA_KEY_DURATION,
				new KeyInfo(MediaMetadata.METADATA_KEY_DURATION, Type.INT));
	}

	// com.android.documentsui.base.Shared.METADATA_VIDEO_LATITUDE
	private static final String METADATA_VIDEO_LATITUDE = "android.media.metadata.video:latitude";
	// com.android.documentsui.base.Shared.METADATA_VIDEO_LONGITUTE
	// XXX typo in AOSP
	private static final String METADATA_VIDEO_LONGITUDE = "android.media.metadata.video:longitude";

	@Override
	public boolean isSupportedMimeType(String mimeType) {
		return mimeType.startsWith("video/");
	}

	@Override
	protected String getMetadataType() {
		// com.android.documentsui.base.Shared.METADATA_KEY_VIDEO
		return "android.media.metadata.video";
	}

	@Override
	protected Map<Integer, KeyInfo> getMetadataKeyMap() {
		return KEY_MAPPING;
	}

	// group 1, 3
	private static final Pattern ISO6709D = Pattern.compile("^([+-]\\d\\d(\\.\\d+)?)([+-]\\d\\d\\d(\\.\\d+)?)");

	private static float[] parseISO6709(String s) {
		var dMatcher = ISO6709D.matcher(s);
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

	@Override
	protected void extractUnmappedMetadata(Bundle typeSpecificMetadata,
			MediaMetadataRetriever mmr) {
		var iso6709 = mmr.extractMetadata(
			MediaMetadataRetriever.METADATA_KEY_LOCATION);
		if (iso6709 != null) {
			var latlng = parseISO6709(iso6709);
			if (latlng != null) {
				// XXX DocumentsUI does not use address locator a la with exif
				typeSpecificMetadata.putFloat(METADATA_VIDEO_LATITUDE, latlng[0]);
				typeSpecificMetadata.putFloat(METADATA_VIDEO_LONGITUDE, latlng[1]);
			}
		}
	}
}
