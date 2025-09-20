package link.xdavidwu.saf.sftp;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.StreamSupport;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.util.io.PathUtils;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.client.extensions.SpaceAvailableExtension;
import org.apache.sshd.sftp.client.extensions.openssh.OpenSSHStatPathExtension;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;

import link.xdavidwu.saf.AbstractUnixLikeDocumentsProvider;
import link.xdavidwu.saf.UncheckedAutoCloseable;

public class SftpDocumentsProvider extends AbstractUnixLikeDocumentsProvider {

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

	protected record FsCreds(
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

	private static final String HEARTBEAT_REQUEST = "keepalive@sftp.saf.xdavidwu.link";
	private static SshClient ssh;
	private ClientSession session;
	static {
		PathUtils.setUserHomeFolderResolver(() -> FileSystems.getDefault().getPath("/"));
		ssh = SshClient.setUpDefaultClient();
		ssh.start();
	}
	private ConnectionParams params;

	private Future<FsCreds> fsCreds;

	// do not start with a dot
	// DocumentsUI identify hidden files by presence of /. in documentId
	// (or display name starting with .)
	private static final String HOME_IDENTIFIER = ":HOME:";

	private StorageManager sm;
	private Handler ioHandler;
	private Handler toastHandler;
	private ExecutorService threadPool = Executors.newCachedThreadPool();

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
		var path = super.pathFromDocumentId(documentId);
		if (path.startsWith("/" + HOME_IDENTIFIER + "/")) {
			return path.substring(HOME_IDENTIFIER.length() + 2);
		} else if (path.equals("/" + HOME_IDENTIFIER)) {
			return ".";
		}
		return path;
	}

	@Override
	protected IOException translateIOException(IOException e) {
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

	private SftpClient getClient() throws IOException {
		// TODO consider allowing newer protocol without permission handling
		// (or find some way to translate id/names)
		return SftpClientFactory.instance()
			.createSftpClient(ensureSession(), 3);
	}

	@Override
	public boolean onCreate() {
		sm = (StorageManager) getContext().getSystemService(Context.STORAGE_SERVICE);
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

	@Override
	public ParcelFileDescriptor openDocument(String documentId, String mode,
			CancellationSignal cancellationSignal)
			throws FileNotFoundException {
		if (!"r".equals(mode)) {
			throw new UnsupportedOperationException(
				"Mode " + mode + " is not supported yet.");
		}
		var sftp = ioToUnchecked(this::getClient);
		String filename = pathFromDocumentId(documentId);
		Log.v("SFTP", "od " + filename);
		var file = ioToUnchecked(() -> sftp.open(filename));
		return ioToUnchecked(() -> sm.openProxyFileDescriptor(
			ParcelFileDescriptor.MODE_READ_ONLY,
			new SftpProxyFileDescriptorCallback(sftp, file),
			ioHandler));
	}

	private FsCreds resolveFsCreds() throws IOException {
		// openDocument, ParcelFileDescriptor checks size, which is not
		// friendly to /proc virtual files
		try (var sftp = getClient()) {
			try (var stream = sftp.read("/proc/self/status")) {
				var reader = new BufferedReader(new InputStreamReader(stream));
				var line = reader.readLine();
				while (line != null) {
					var parts = line.split("\t");
					int uid = 0, gid = 0;
					int[] groups = null;
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
			fsCreds = threadPool.submit(this::resolveFsCreds);
		}
	}

	private Optional<FsCreds> getFsCreds() {
		try {
			return Optional.of(fsCreds.get());
		} catch (ExecutionException|InterruptedException e) {
			toast("SFTP: Cannot resolve identity: " + e.getCause().getMessage());
		}
		return Optional.empty();
	}

	private int getModeBits(SftpClient.Attributes stat) {
		var mode = stat.getPermissions();
		return getFsCreds().map(creds -> {
			if (creds.hasCapability(FsCreds.CAP_DAC_OVERRIDE)) {
				return 7;
			}
			var bits = (
				creds.uid() == stat.getUserId() ? mode >> 6 :
				creds.hasGroup(stat.getGroupId()) ? mode >> 3 :
				mode
			) & 7;
			if (creds.hasCapability(FsCreds.CAP_DAC_READ_SEARCH)) {
				bits |= S_IR & S_IX;
			}
			return bits;
		}).orElse(7);
	}

	private boolean hasModeBit(SftpClient.Attributes stat, int bit) {
		return (getModeBits(stat) & bit) == bit;
	}

	private Object[] getDocumentRow(SftpClient sftp, Cursor cursor,
			String documentId, SftpClient.Attributes lstat) {
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
			var flags = 0;
			if (stat.isSymbolicLink()) {
				flags |= Document.FLAG_PARTIAL;
			}
			if (typeSupportsMetadata(type) && hasModeBit(stat, S_IR)) {
				flags |= Document.FLAG_SUPPORTS_METADATA;
			}
			if (typeSupportsThumbnail(type) && hasModeBit(stat, S_IR)) {
				flags |= Document.FLAG_SUPPORTS_THUMBNAIL;
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
			throws FileNotFoundException, HaltWithCursorException;
	}

	protected Cursor performQuery(Cursor c, SftpQueryOperation o)
			throws FileNotFoundException {
		return performQuery(c, () -> {
			try (var sftp = new UncheckedAutoCloseable<SftpClient>(
					ioWithCursor(c, this::getClient)
					.orElseThrow(this::haltIt))) {
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
			// lazy until iterator creation, openDir at iterator
			var entries = ioWithCursor(result,
				() -> sftp.readDir(filename).spliterator())
					.orElseThrow(this::haltIt);

			StreamSupport.stream(entries, false)
				.filter(entry -> !List.of(".", "..").contains(entry.getFilename()))
				.map(entry -> {
					var documentId = parentDocumentId + '/' + entry.getFilename();
					return getDocumentRow(sftp, result, documentId,
							entry.getAttributes());
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
				.orElseThrow(this::haltIt);
			result.addRow(getDocumentRow(sftp, result, documentId, stat));
		});
	}

	public Cursor queryRoots(String[] projection) {
		var cols = projection != null ? projection : DEFAULT_ROOT_PROJECTION;
		var result = new MatrixCursor(cols);

		var sp = PreferenceManager.getDefaultSharedPreferences(getContext());
		var mountpoint = sp.getString("mountpoint", ".");
		var root = mountpoint;
		if (mountpoint.equals("") || mountpoint.equals(".")) {
			root = HOME_IDENTIFIER;
		} else if (mountpoint.startsWith("./")) {
			root = HOME_IDENTIFIER + mountpoint.substring(1);
		} else if (!mountpoint.startsWith("/")) {
			root = HOME_IDENTIFIER + "/" + mountpoint;
		}
		var documentId = documentIdFromPath(root);
		var rootUri = getRootUri();

		var bytesInfo = mustIOWithCursor(result, () -> {
			// unlike performQuery, connection/auth failure is not fatal here
			var sftp = getClient();
			var path = pathFromDocumentId(documentId);

			var spaceAvailable = sftp.getExtension(SpaceAvailableExtension.class);
			if (spaceAvailable.isSupported()) {
				var info = spaceAvailable.available(path);

				return new Long[]{
					info.bytesOnDevice,
					info.bytesAvailableToUser
				};
			}

			var statvfs = sftp.getExtension(OpenSSHStatPathExtension.class);
			if (statvfs.isSupported()) {
				var info = statvfs.stat(path);

				return new Long[]{
					info.f_blocks * info.f_frsize,
					info.f_bavail * info.f_frsize
				};
			}
			return null;
		}, DocumentsContract.EXTRA_INFO, "cannot statvfs: ").orElse(null);

		result.addRow(Arrays.stream(cols).map(c -> switch(c) {
		case Root.COLUMN_ROOT_ID -> rootUri.toString();
		case Root.COLUMN_DOCUMENT_ID -> documentId;
		case Root.COLUMN_FLAGS -> Root.FLAG_SUPPORTS_IS_CHILD;
		case Root.COLUMN_TITLE ->
			String.format("%s@%s:%s", params.username(), params.host(), mountpoint);
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
