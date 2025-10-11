package link.xdavidwu.saf.sftp;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.style.TypefaceSpan;
import android.util.Base64;

import java.net.SocketAddress;
import java.security.PublicKey;
import java.util.concurrent.CompletableFuture;

import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;

public class SshServerKeyVerifier implements ServerKeyVerifier {
	private Activity activity;

	public SshServerKeyVerifier(Activity activity) {
		this.activity = activity;
	}

	@Override
	public boolean verifyServerKey(ClientSession session,
			SocketAddress remoteAddress, PublicKey serverKey) {
		var acceptFuture = new CompletableFuture<Boolean>();

		var raw = new ByteArrayBuffer();
		raw.putRawPublicKey(serverKey);
		var key64 = new SpannableString(
			Base64.encodeToString(raw.array(), 0, raw.wpos(), Base64.DEFAULT));
		var md5 = new SpannableString(
			KeyUtils.getFingerPrint(BuiltinDigests.md5, serverKey));
		var sha256 = new SpannableString(
			KeyUtils.getFingerPrint(BuiltinDigests.sha256, serverKey));

		key64.setSpan(new TypefaceSpan("monospace"), 0, key64.length(),
			Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
		md5.setSpan(new TypefaceSpan("monospace"), 0, md5.length(),
			Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
		sha256.setSpan(new TypefaceSpan("monospace"), 0,
			sha256.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

		activity.runOnUiThread(() -> {
			new AlertDialog.Builder(activity)
				.setTitle("Host key verification")
				.setMessage(new SpannableStringBuilder(
						"Accept SSH server key of type " +
						KeyUtils.getKeyType(serverKey) + ": ")
					.append(key64).append("?\n")
					.append(md5).append("\n")
					.append(sha256))
				.setCancelable(false)
				.setPositiveButton("Accept", (dialog, which) -> {
					acceptFuture.complete(true);
				}).setNegativeButton("Deny", (dialog, which) -> {
					acceptFuture.complete(false);
				})
				.show();
		});

		return acceptFuture.join();
	}
}
