package link.xdavidwu.saf.sftp;

import android.content.SharedPreferences;
import android.net.Uri;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.time.Duration;
import java.util.List;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.cipher.BuiltinCiphers;
import org.apache.sshd.common.mac.BuiltinMacs;
import org.apache.sshd.common.util.io.PathUtils;
import org.apache.sshd.common.util.security.SecurityProviderChoice;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.contrib.common.util.security.androidopenssl.AndroidOpenSSLSecurityProviderRegistrar;
import org.apache.sshd.core.CoreModuleProperties;

public record SftpConnectionParameters(String host, int port,
		String username, String password, String remotePath) {
	private static SshClient ssh;
	static {
		PathUtils.setUserHomeFolderResolver(() -> FileSystems.getDefault().getPath("/"));
		SecurityUtils.registerSecurityProvider(
			new AndroidOpenSSLSecurityProviderRegistrar());
		SecurityUtils.setDefaultProviderChoice(
			SecurityProviderChoice.toSecurityProviderChoice(
				AndroidOpenSSLSecurityProviderRegistrar.NAME));
		ssh = SshClient.setUpDefaultClient();

		// SAF seems to read at most 128k per request
		CoreModuleProperties.MAX_PACKET_SIZE.set(ssh, 0x40000L);

		// org.apache.sshd.common.BaseBuilder.DEFAULT_CIPHERS_PREFERENCE
		// reorder by performance
		ssh.setCipherFactories(List.of(
			BuiltinCiphers.aes128ctr,
			BuiltinCiphers.aes128cbc,
			BuiltinCiphers.aes128gcm,
			BuiltinCiphers.aes192ctr,
			BuiltinCiphers.aes192cbc,
			BuiltinCiphers.aes256ctr,
			BuiltinCiphers.aes256cbc,
			BuiltinCiphers.aes256gcm,
			BuiltinCiphers.cc20p1305_openssh));
		// org.apache.sshd.common.BaseBuilder.DEFAULT_MAC_PREFERENCE
		// reorder by performance
		ssh.setMacFactories(List.of(
			BuiltinMacs.hmacsha1etm,
			BuiltinMacs.hmacsha1,
			BuiltinMacs.hmacsha512etm,
			BuiltinMacs.hmacsha512,
			BuiltinMacs.hmacsha256etm,
			BuiltinMacs.hmacsha256));

		ssh.start();
	}

	public static SftpConnectionParameters fromSharedPreferences(
			SharedPreferences sp) {
		var remotePath = sp.getString("mountpoint", ".");
		if ("".equals(remotePath)) {
			remotePath = ".";
		}
		return new SftpConnectionParameters(
			sp.getString("host", ""),
			Integer.parseInt(sp.getString("port", "22")),
			sp.getString("username", ""),
			sp.getString("passwd", ""),
			remotePath
		);
	}

	public ClientSession preAuth() throws IOException {
		var session = ssh.connect(username, host, port)
			.verify(Duration.ofSeconds(3))
			.getClientSession();
		session.addPasswordIdentity(password);
		return session;
	}

	public ClientSession connect() throws IOException {
		var session = preAuth();
		session.auth().verify(Duration.ofSeconds(3));
		return session;
	}

	public Uri getRootUri() {
		return new Uri.Builder().scheme("sftp")
			.authority(username + "@" + host + ":" + port).build();
	}
}
