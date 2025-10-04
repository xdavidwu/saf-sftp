package link.xdavidwu.saf.sftp;

import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.storage.StorageManager;
import android.os.StrictMode;
import android.preference.PreferenceManager;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.widget.Toast;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.SshException;
import org.apache.sshd.common.channel.Channel;
import org.apache.sshd.common.channel.ChannelListener;
import org.apache.sshd.common.channel.exception.SshChannelOpenException;
import org.apache.sshd.common.util.io.PathUtils;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.client.extensions.SpaceAvailableExtension;
import org.apache.sshd.sftp.client.extensions.openssh.OpenSSHStatPathExtension;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;

import link.xdavidwu.saf.AbstractUnixLikeDocumentsProvider;
import link.xdavidwu.saf.PerformsIO;
import link.xdavidwu.saf.UncheckedAutoCloseable;

public class SftpDocumentsProvider extends AbstractUnixLikeDocumentsProvider
		implements PerformsIO {
	private static final String TAG = "SFTP";

	protected record ConnectionParams(String host, int port,
			String username, String password) {

		protected ClientSession connect() throws IOException {
			var session = ssh.connect(username, host, port)
				.verify(Duration.ofSeconds(3))
				.getClientSession();
			session.addPasswordIdentity(password);
			session.auth().verify(Duration.ofSeconds(3));
			return session;
		}

		protected Uri getRootUri() {
			return new Uri.Builder().scheme("sftp")
				.authority(username + "@" + host + ":" + port).build();
		}
	}

	protected static class ChannelClosedFutureAdaptor implements ChannelListener {
		private CompletableFuture<Void> future = new CompletableFuture<>();

		@Override
		public void channelClosed(Channel c, Throwable r) {
			Log.i(TAG, "channel released");
			future.complete(null);
		}

		public CompletableFuture<Void> future() {
			return future;
		}
	}

	private static final String HEARTBEAT_REQUEST = "keepalive@sftp.saf.xdavidwu.link";
	private static SshClient ssh;
	private ClientSession session;
	static {
		PathUtils.setUserHomeFolderResolver(() -> FileSystems.getDefault().getPath("/"));
		ssh = SshClient.setUpDefaultClient();
		ssh.start();
	}
	private ConnectionParams params;
	private String remotePath;

	private CompletableFuture<Optional<FsCreds>> fsCreds;

	private StorageManager sm;
	private Handler ioHandler;
	private Handler toastHandler;

	private static final String[] DEFAULT_ROOT_PROJECTION = new String[] {
		Root.COLUMN_ROOT_ID,
		Root.COLUMN_FLAGS,
		Root.COLUMN_ICON,
		Root.COLUMN_TITLE,
		Root.COLUMN_DOCUMENT_ID,
		Root.COLUMN_CAPACITY_BYTES,
		Root.COLUMN_AVAILABLE_BYTES,
	};

	private static final String[] DEFAULT_DOC_PROJECTION = new String[] {
		Document.COLUMN_DOCUMENT_ID,
		Document.COLUMN_DISPLAY_NAME,
		Document.COLUMN_MIME_TYPE,
		Document.COLUMN_LAST_MODIFIED,
		Document.COLUMN_SIZE,
		Document.COLUMN_FLAGS,
		Document.COLUMN_SUMMARY,
		Document.COLUMN_ICON,
	};

	@Override
	protected Uri getRootUri() {
		return params.getRootUri();
	}

	@Override
	protected String pathFromDocumentId(String documentId) {
		return remotePath + super.pathFromDocumentId(documentId);
	}

	@Override
	public IOException translateIOException(IOException e) {
		if (e instanceof SftpException s) {
			switch (s.getStatus()) {
			case SftpConstants.SSH_FX_NO_SUCH_FILE:
			case SftpConstants.SSH_FX_NO_SUCH_PATH:
				var fnf = new FileNotFoundException(s.getMessage());
				fnf.initCause(s);
				return fnf;
			default:
				return s;
			}
		}
		return e;
	}

	private void toast(String msg) {
		toastHandler.sendMessage(toastHandler.obtainMessage(0, msg));
	}

	private SharedPreferences.OnSharedPreferenceChangeListener loadConfig =
			(sp, key) -> {
		params = new ConnectionParams(
			sp.getString("host", ""),
			Integer.parseInt(sp.getString("port", "22")),
			sp.getString("username", ""),
			sp.getString("passwd", "")
		);
		remotePath = sp.getString("mountpoint", ".");
		if ("".equals(remotePath)) {
			remotePath = ".";
		}
		if (session != null) {
			try {
				session.close();
			} catch (IOException e) {}
		}
		session = null;
		fsCreds = null;
	};

	private ClientSession ensureSession() throws IOException {
		// /shrug if we are somehow invoked on main thread
		StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX);

		if (session != null) {
			// org.apache.sshd.client.session.ClientConnectionService::sendHeartBeat
			var buf = session.createBuffer(
				SshConstants.SSH_MSG_GLOBAL_REQUEST,
				HEARTBEAT_REQUEST.length() + Byte.SIZE);
			buf.putString(HEARTBEAT_REQUEST);
			buf.putBoolean(true);
			try {
				session.request(HEARTBEAT_REQUEST, buf,
					Duration.ofSeconds(10));
				return session;
			} catch (IOException e) {
				// try new session
			}
			session.close();
			session = null;
		}
		session = params.connect();
		return session;
	}

	private synchronized SftpClient getClient(CancellationSignal signal) throws IOException {
		var session = ensureSession();
		var retries = 3;
		while (true) {
			try {
				// TODO consider allowing newer protocol without permission handling
				// (or find some way to translate id/names)
				return SftpClientFactory.instance()
					.createSftpClient(session, 3);
			} catch (SshException e) {
				var c = e.getCause();
				if (c instanceof SshChannelOpenException s) {
					var code = s.getReasonCode();
					// openssh uses SSH_OPEN_CONNECT_FAILED
					var temporary = code == SshConstants.SSH_OPEN_CONNECT_FAILED ||
						code == SshConstants.SSH_OPEN_RESOURCE_SHORTAGE;
					if (temporary && retries-- != 0) {
						Log.i(TAG, "temporary open channel failure (hitting concurrent channels limit on server?), waiting for any existing channel to close");
						if (signal != null) {
							signal.throwIfCanceled();
						}

						var adaptor = new ChannelClosedFutureAdaptor();
						session.addChannelListener(adaptor);
						try {
							adaptor.future().get(1, TimeUnit.SECONDS);
						} catch (TimeoutException|ExecutionException|InterruptedException e2) {}
						session.removeChannelListener(adaptor);
						continue;
					}
				}
				throw e;
			}
		}
	}

	private SftpClient getClient() throws IOException {
		return getClient(null);
	}

	@Override
	public boolean onCreate() {
		sm = getContext().getSystemService(StorageManager.class);
		var ioThread = new HandlerThread("IO thread");
		ioThread.start();
		ioHandler = new Handler(ioThread.getLooper());
		toastHandler = new Handler(Looper.getMainLooper(), msg -> {
			Toast.makeText(getContext(), "SAF-SFTP: " + msg.obj.toString(),
				Toast.LENGTH_LONG).show();
			return true;
		});

		var sp = PreferenceManager.getDefaultSharedPreferences(getContext());
		sp.registerOnSharedPreferenceChangeListener(loadConfig);
		loadConfig.onSharedPreferenceChanged(sp, "");
		return true;
	}

	private static final Map<SftpClient.OpenMode, Integer> MODE_MAPPING = new EnumMap<>(SftpClient.OpenMode.class);
	static {
		MODE_MAPPING.put(SftpClient.OpenMode.Read, ParcelFileDescriptor.MODE_READ_ONLY);
		MODE_MAPPING.put(SftpClient.OpenMode.Write, ParcelFileDescriptor.MODE_WRITE_ONLY);
		// MODE_READ_WRITE is OR of above
		MODE_MAPPING.put(SftpClient.OpenMode.Append, ParcelFileDescriptor.MODE_APPEND);
		MODE_MAPPING.put(SftpClient.OpenMode.Truncate, ParcelFileDescriptor.MODE_TRUNCATE);
		MODE_MAPPING.put(SftpClient.OpenMode.Create, ParcelFileDescriptor.MODE_CREATE);
	}

	@Override
	public ParcelFileDescriptor openDocument(String documentId, String mode,
			CancellationSignal signal)
			throws FileNotFoundException {
		int parcelFileDescriptorMode = ParcelFileDescriptor.parseMode(mode);
		var sftpModes = EnumSet.noneOf(SftpClient.OpenMode.class);
		int[] remainingBits = {parcelFileDescriptorMode};
		MODE_MAPPING.forEach((sftpMode, bit) -> {
			if ((remainingBits[0] & bit) == bit) {
				sftpModes.add(sftpMode);
				remainingBits[0] ^= bit;
			}
		});

		if (remainingBits[0] != 0) {
			throw new UnsupportedOperationException(
				"Mode " + mode + " is not supported yet.");
		}

		var sftp = ioToUnchecked(() -> getClient(signal));
		String filename = pathFromDocumentId(documentId);
		SftpClient.CloseableHandle file;
		try {
			file = ioToUnchecked(() -> sftp.open(filename, sftpModes));
		} catch (FileNotFoundException|UncheckedIOException e) {
			try {
				sftp.close();
			} catch (IOException e2) {}
			throw e;
		}

		return ioToUnchecked(() -> sm.openProxyFileDescriptor(
			parcelFileDescriptorMode,
			new SftpProxyFileDescriptorCallback(sftp, file, getContext()),
			ioHandler));
	}

	@Override
	public String createDocument(String parentDocumentId, String mimeType,
			String displayName) throws FileNotFoundException {
		var parent = pathFromDocumentId(parentDocumentId);
		if (Document.MIME_TYPE_DIR.equals(mimeType)) {
			ioToUnchecked(() -> {
				try (var sftp = getClient()) {
					sftp.mkdir(parent + "/" + displayName);
				}
				return null;
			});
		} else {
			ioToUnchecked(() -> {
				try (var sftp = getClient()) {
					sftp.close(
						sftp.open(parent + "/" + displayName,
							SftpClient.OpenMode.Create,
							SftpClient.OpenMode.Exclusive));
				}
				return null;
			});
		}
		// TODO notify child change
		return parent + "/" + displayName;
	}

	@Override
	public void deleteDocument(String documentId)
			throws FileNotFoundException {
		var path = pathFromDocumentId(documentId);
		ioToUnchecked(() -> {
			try (var sftp = getClient()) {
				sftp.remove(path);
			}
			return null;
		});
	}

	private FsCreds resolveFsCreds() throws IOException {
		// openDocument, ParcelFileDescriptor checks size, which is not
		// friendly to /proc virtual files
		try (var sftp = getClient()) {
			try (var stream = sftp.read("/proc/self/status")) {
				var reader = new BufferedReader(new InputStreamReader(stream));
				var line = reader.readLine();
				int uid = 0, gid = 0;
				int[] groups = null;
				while (line != null) {
					var parts = line.split("\t");
					switch (parts[0]) {
					case "Uid:":
						uid = Integer.valueOf(parts[4]);
						break;
					case "Gid:":
						gid = Integer.valueOf(parts[4]);
						break;
					case "Groups:":
						groups = Arrays.stream(parts[1].split(" "))
							.mapToInt(s -> Integer.valueOf(s)).toArray();
						break;
					case "CapEff:":
						return new FsCreds(uid, gid, groups,
							Long.parseUnsignedLong(parts[1], 16));
					}
					line = reader.readLine();
				}
			}
		}
		return null;
	}

	private void hoistFsCreds() {
		if (fsCreds == null) {
			fsCreds = CompletableFuture.supplyAsync(() -> {
				try {
					return Optional.of(resolveFsCreds());
				} catch (IOException e) {
					toast("Cannot resolve identity: " + e.getMessage());
					Log.e(TAG, "cannot resolve identify", e);
				}
				return Optional.empty();
			});
		}
	}

	private int getModeBits(SftpClient.Attributes stat) {
		return getModeBits(
			stat.getPermissions(), stat.getUserId(), stat.getGroupId(),
			fsCreds.join().orElse(null));
	}

	private boolean hasModeBit(SftpClient.Attributes stat, int bit) {
		return (getModeBits(stat) & bit) == bit;
	}

	private Object[] getDocumentRow(SftpClient sftp, Cursor cursor,
			String documentId, SftpClient.Attributes lstat,
			SftpClient.Attributes parentStat) {
		var name = basename(documentId);
		var path = pathFromDocumentId(documentId);
		var tmp = lstat;
		if (lstat.isSymbolicLink()) {
			try {
				tmp = ioWithCursor(cursor,
					() -> sftp.stat(path), DocumentsContract.EXTRA_INFO,
					"Cannot stat " + name + ": ").orElse(lstat);
			} catch (FileNotFoundException e) {}
		}
		final var stat = tmp;
		var type = getType(stat.getPermissions(), name);

		// TODO handle broken symlink
		return Arrays.stream(cursor.getColumnNames()).map(c -> switch (c) {
		case Document.COLUMN_DOCUMENT_ID -> documentId;
		case Document.COLUMN_DISPLAY_NAME -> name;
		case Document.COLUMN_MIME_TYPE -> type;
		case Document.COLUMN_SIZE -> lstat.getSize();
		case Document.COLUMN_LAST_MODIFIED -> lstat.getModifyTime().toMillis();
		case Document.COLUMN_FLAGS -> {
			var flags = switch (stat.getPermissions() & S_IFMT) {
			case S_IFLNK -> Document.FLAG_PARTIAL;
			case S_IFDIR -> hasModeBit(stat, S_IW | S_IX) ?
				Document.FLAG_DIR_SUPPORTS_CREATE : 0;
			case S_IFREG -> {
				var rflags = hasModeBit(stat, S_IW) ?
					Document.FLAG_SUPPORTS_WRITE : 0;
				if (hasModeBit(stat, S_IR)) {
					if (typeSupportsMetadata(type)) {
						rflags |= Document.FLAG_SUPPORTS_METADATA;
					}
					if (typeSupportsThumbnail(type)) {
						rflags |= Document.FLAG_SUPPORTS_THUMBNAIL;
					}
				}
				yield rflags;
			}
			default -> 0;
			};
			if (!stat.isDirectory() && hasModeBit(parentStat, S_IW)) {
				// TODO sticky
				flags |= Document.FLAG_SUPPORTS_DELETE;
			}
			yield flags;
		}
		case Document.COLUMN_ICON -> {
			if (lstat.isSymbolicLink() && stat.isDirectory()) {
				// DocumentsUI grid view is hard-coded to system folder icon
				yield R.drawable.ic_symlink_to_dir;
			} else if (stat.isSymbolicLink()) {
				yield R.drawable.ic_broken_symlink;
			}
			yield null;
		}
		case Document.COLUMN_SUMMARY -> {
			if (stat.isSymbolicLink()) {
				yield mustIOWithCursor(cursor, () -> sftp.readLink(path),
					DocumentsContract.EXTRA_INFO,
					"Cannot readlink " + name + ": ")
					.map(s -> "Symlink to " + s).orElse(null);
			}
			yield null;
		}
		default -> null;
		}).toArray();
	}

	protected interface SftpQueryOperation {
		public void execute(SftpClient sftp)
			throws FileNotFoundException, AbortWithCursorException;
	}

	protected Cursor performQuery(Cursor c, SftpQueryOperation o)
			throws FileNotFoundException {
		return performQuery(c, () -> {
			try (var sftp = new UncheckedAutoCloseable<SftpClient>(
					ioWithCursor(c, this::getClient)
					.orElseThrow(this::abortQuery))) {
				o.execute(sftp.c());
			}
		});
	}

	public Cursor queryChildDocuments(
			String parentDocumentId, String[] projection, String sortOrder)
			throws FileNotFoundException {
		var cols = projection != null ? projection : DEFAULT_DOC_PROJECTION;
		var result = new MatrixCursor(cols);
		hoistFsCreds();

		return performQuery(result, sftp -> {
			var filename = pathFromDocumentId(parentDocumentId);
			var entries = ioWithCursor(result,
				() -> sftp.readEntries(filename))
					.orElseThrow(this::abortQuery);

			// protocol doesn't really say anything about . or ..
			var parentStat = entries.stream()
				.filter(entry -> ".".equals(entry.getFilename()))
				.findFirst().map(entry -> entry.getAttributes())
				.orElseGet(() -> {
					Log.i(TAG, "server does not send .");

					return mustIOToUnchecked(() -> sftp.stat(filename));
				});

			entries.stream()
				.filter(entry -> !List.of(".", "..").contains(entry.getFilename()))
				.map(entry -> {
					var documentId = parentDocumentId + '/' + entry.getFilename();
					return getDocumentRow(sftp, result, documentId,
							entry.getAttributes(), parentStat);
				}).forEach(row -> result.addRow(row));
		});
	}

	public Cursor queryDocument(String documentId, String[] projection)
			throws FileNotFoundException {
		var cols = projection != null ? projection : DEFAULT_DOC_PROJECTION;
		var result = new MatrixCursor(cols);
		hoistFsCreds();

		return performQuery(result, sftp -> {
			var path = pathFromDocumentId(documentId);
			var stat = ioWithCursor(result, () -> sftp.lstat(path))
				.orElseThrow(this::abortQuery);

			var dirIndex = path.lastIndexOf("/");
			var parentPath = dirIndex != -1 ? path.substring(0, dirIndex + 1) : ".";
			var parentStat = ioWithCursor(result,
					() -> sftp.stat(parentPath))
				.orElseThrow(this::abortQuery);

			result.addRow(getDocumentRow(sftp, result, documentId, stat, parentStat));
		});
	}

	public Cursor queryRoots(String[] projection) {
		var cols = projection != null ? projection : DEFAULT_ROOT_PROJECTION;
		var result = new MatrixCursor(cols);

		var rootUri = getRootUri().toString();

		var bytesInfo = mustIOWithCursor(result, () -> {
			// unlike performQuery, connection/auth failure is not fatal here
			try (var sftp = getClient()) {
				var spaceAvailable = sftp.getExtension(SpaceAvailableExtension.class);
				if (spaceAvailable.isSupported()) {
					var info = spaceAvailable.available(remotePath + '/');

					return new Long[]{
						info.bytesOnDevice,
						info.bytesAvailableToUser
					};
				}

				var statvfs = sftp.getExtension(OpenSSHStatPathExtension.class);
				if (statvfs.isSupported()) {
					var info = statvfs.stat(remotePath + '/');

					return new Long[]{
						info.f_blocks * info.f_frsize,
						info.f_bavail * info.f_frsize
					};
				}
			}
			return null;
		}, DocumentsContract.EXTRA_INFO, "cannot statvfs: ").orElse(null);

		result.addRow(Arrays.stream(cols).map(c -> switch(c) {
		case Root.COLUMN_ROOT_ID -> rootUri;
		case Root.COLUMN_DOCUMENT_ID -> rootUri;
		case Root.COLUMN_FLAGS ->
			Root.FLAG_SUPPORTS_IS_CHILD | Root.FLAG_SUPPORTS_CREATE;
		case Root.COLUMN_TITLE ->
			String.format("%s@%s:%s", params.username(), params.host(), remotePath);
		case Root.COLUMN_ICON -> R.mipmap.sym_def_app_icon;
		// DocumentsUI shows localized and humanized COLUMN_AVAILABLE_BYTES
		// when summary is not present, which is more useful and nicer
		// case Root.COLUMN_SUMMARY -> "SFTP with user: " + params.username();
		case Root.COLUMN_CAPACITY_BYTES ->
			bytesInfo != null ? bytesInfo[0] : null;
		case Root.COLUMN_AVAILABLE_BYTES ->
			bytesInfo != null ? bytesInfo[1] : null;
		default -> null;
		}).toArray());

		return result;
	}
}
