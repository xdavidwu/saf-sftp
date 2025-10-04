package link.xdavidwu.saf.metadata;

import android.media.MediaMetadata;
import android.media.MediaMetadataRetriever;

import java.util.HashMap;
import java.util.Map;

public class AudioMetadataProvider extends AbstractMediaMetadataProvider {
	private static final Map<Integer, KeyInfo> KEY_MAPPING = new HashMap<>();
	static {
		KEY_MAPPING.put(MediaMetadataRetriever.METADATA_KEY_DURATION,
				new KeyInfo(MediaMetadata.METADATA_KEY_DURATION, Type.INT));
		KEY_MAPPING.put(MediaMetadataRetriever.METADATA_KEY_ARTIST,
				new KeyInfo(MediaMetadata.METADATA_KEY_ARTIST));
		KEY_MAPPING.put(MediaMetadataRetriever.METADATA_KEY_COMPOSER,
				new KeyInfo(MediaMetadata.METADATA_KEY_COMPOSER));
		KEY_MAPPING.put(MediaMetadataRetriever.METADATA_KEY_ALBUM,
				new KeyInfo(MediaMetadata.METADATA_KEY_ALBUM));
	}

	@Override
	public boolean isSupportedMimeType(String mimeType) {
		return mimeType.startsWith("audio/");
	}

	@Override
	protected String getMetadataType() {
		// com.android.documentsui.base.Shared.METADATA_KEY_AUDIO
		return "android.media.metadata.audio";
	}

	@Override
	protected Map<Integer, KeyInfo> getMetadataKeyMap() {
		return KEY_MAPPING;
	}
}
